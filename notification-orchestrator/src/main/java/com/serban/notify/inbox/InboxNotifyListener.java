package com.serban.notify.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.repository.NotificationInboxRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background PostgreSQL {@code LISTEN inbox_updated} worker for cross-pod
 * SSE event broadcast (Faz 23.4 PR-E.4).
 *
 * <p>One DEDICATED long-lived JDBC connection per pod, allocated via
 * {@link PGSimpleDataSource} (NOT shared with main HikariCP pool — Codex
 * iter-1 P1.1 absorb). LISTEN blocks indefinitely on
 * {@link PGConnection#getNotifications(int)}; if drawn from the main pool
 * it would starve the pool of one connection forever per pod. Dedicated
 * unpooled connection isolates this lifecycle.
 *
 * <p>On startup, executes {@code LISTEN inbox_updated}; a daemon thread
 * polls every {@link #POLL_INTERVAL_MS}. On NOTIFY arrival, parses
 * {@code {"orgId":...,"subscriberId":...}} payload, recomputes fresh
 * unread count via {@link NotificationInboxRepository#countUnreadBySubscriber}
 * (Codex iter-1 P2.1 absorb — payload no longer carries pre-computed count
 * to avoid stale-snapshot race in concurrent mutations), and re-emits
 * Spring {@link InboxUpdatedEvent}.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link PostConstruct}: build PGSimpleDataSource from spring.datasource
 *       config + connect + LISTEN + start poll thread</li>
 *   <li>{@link PreDestroy}: stop poll thread + UNLISTEN explicit + close connection</li>
 *   <li>Connection drop / SQLException: log + reconnect with exponential backoff</li>
 * </ul>
 *
 * <p>Disabled when {@code notify.inbox.cross-pod-enabled=false} (single-pod
 * test / local dev).
 *
 * <p>Origin-pod duplicate: NOTIFY is delivered to ALL listeners including
 * the originating session. Pod A publishes → Pod A's listener also receives.
 * This is intentional: the publisher does NOT directly invoke
 * {@code applicationEventPublisher} (when cross-pod enabled), so the LISTEN
 * path is the sole event source — uniform delivery, no double-firing.
 */
@Component
public class InboxNotifyListener {

    private static final Logger log = LoggerFactory.getLogger(InboxNotifyListener.class);

    /** PG NOTIFY channel name — must match {@link InboxEventPublisher#NOTIFY_CHANNEL}. */
    public static final String CHANNEL = "inbox_updated";

    /** Poll interval (ms) — getNotifications(timeout) returns when notification arrives or timeout. */
    private static final int POLL_INTERVAL_MS = 1_000;

    /** Reconnect backoff base (ms). Exponential up to MAX_BACKOFF_MS. */
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;
    private final NotificationInboxRepository inboxRepository;

    @Value("${notify.inbox.cross-pod-enabled:true}")
    private boolean enabled;

    /**
     * Spring DataSource JDBC URL — used to build dedicated PGSimpleDataSource
     * (NOT the autoconfigured HikariDataSource, which would lend a pool
     * connection to LISTEN forever).
     */
    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread listenThread;
    private Connection currentConnection;
    private PGSimpleDataSource dedicatedDataSource;

    public InboxNotifyListener(
        ApplicationEventPublisher applicationEventPublisher,
        ObjectMapper objectMapper,
        NotificationInboxRepository inboxRepository
    ) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.objectMapper = objectMapper;
        this.inboxRepository = inboxRepository;
    }

    @PostConstruct
    public void initialize() {
        if (!enabled) {
            log.info("inbox LISTEN disabled (cross-pod-enabled=false) — single-pod fallback");
            return;
        }
        // Dedicated unpooled DataSource — does NOT share HikariCP pool.
        dedicatedDataSource = new PGSimpleDataSource();
        dedicatedDataSource.setUrl(jdbcUrl);
        dedicatedDataSource.setUser(dbUsername);
        dedicatedDataSource.setPassword(dbPassword);

        running.set(true);
        listenThread = new Thread(this::listenLoop, "inbox-pg-listen");
        listenThread.setDaemon(true);
        listenThread.start();
        log.info("inbox LISTEN started: channel={} (dedicated PGSimpleDataSource)", CHANNEL);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (listenThread != null) {
            listenThread.interrupt();
            try {
                listenThread.join(5_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        unlistenAndCloseConnection();
        log.info("inbox LISTEN stopped");
    }

    /**
     * Main poll loop. Reconnects with exponential backoff on connection
     * failure. Exits when {@code running=false} (PreDestroy).
     */
    private void listenLoop() {
        long backoff = INITIAL_BACKOFF_MS;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                openConnectionAndListen();
                backoff = INITIAL_BACKOFF_MS;  // reset on successful connect
                pollNotifications();
            } catch (SQLException sqle) {
                if (!running.get()) break;
                log.warn("inbox LISTEN connection error: {} — reconnect in {}ms",
                    sqle.getMessage(), backoff);
                closeConnectionQuietly();
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            } catch (Exception ex) {
                if (!running.get()) break;
                log.error("inbox LISTEN unexpected error: {}", ex.getMessage(), ex);
                try {
                    Thread.sleep(INITIAL_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("inbox LISTEN loop exit");
    }

    private void openConnectionAndListen() throws SQLException {
        currentConnection = dedicatedDataSource.getConnection();
        currentConnection.setAutoCommit(true);
        // Execute LISTEN; channel name is constant, no SQL injection risk
        try (Statement stmt = currentConnection.createStatement()) {
            stmt.execute("LISTEN " + CHANNEL);
        }
    }

    /**
     * Poll loop body: getNotifications(timeout) blocks until NOTIFY arrives
     * or timeout fires. Each batch processed; loop continues until exception
     * or shutdown.
     */
    private void pollNotifications() throws SQLException {
        PGConnection pgConn = currentConnection.unwrap(PGConnection.class);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            // PG driver: getNotifications(timeout) blocks up to timeout ms.
            PGNotification[] notifications = pgConn.getNotifications(POLL_INTERVAL_MS);
            if (notifications == null) continue;
            for (PGNotification notif : notifications) {
                handleNotification(notif);
            }
        }
    }

    /**
     * Parse JSON payload + recompute fresh unread count + emit Spring event.
     *
     * <p>Codex iter-1 P2.1 absorb: payload is dirty-flag only ({@code orgId},
     * {@code subscriberId}); count recomputed POST-COMMIT here (PG NOTIFY
     * delivers only after publisher's transaction committed → repository
     * sees the latest committed state). Avoids stale-snapshot race.
     */
    private void handleNotification(PGNotification notif) {
        if (!CHANNEL.equals(notif.getName())) return;
        String payload = notif.getParameter();
        if (payload == null || payload.isEmpty()) {
            log.debug("inbox NOTIFY received with empty payload — skip");
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            String orgId = node.path("orgId").asText(null);
            String subscriberId = node.path("subscriberId").asText(null);
            if (orgId == null || subscriberId == null) {
                log.warn("inbox NOTIFY skip (missing fields): payload={}", payload);
                return;
            }
            long unreadCount = inboxRepository.countUnreadBySubscriber(orgId, subscriberId);
            applicationEventPublisher.publishEvent(
                new InboxUpdatedEvent(orgId, subscriberId, unreadCount)
            );
            log.debug("inbox NOTIFY received: orgId={} subscriberId={} unreadCount={} (recomputed)",
                orgId, subscriberId, unreadCount);
        } catch (Exception ex) {
            log.warn("inbox NOTIFY parse/recompute error (drop): payload={} err={}",
                payload, ex.getMessage());
        }
    }

    private void unlistenAndCloseConnection() {
        if (currentConnection != null) {
            try (Statement stmt = currentConnection.createStatement()) {
                stmt.execute("UNLISTEN " + CHANNEL);
            } catch (SQLException ignore) {
                // best-effort; close will implicitly unlisten anyway
            }
        }
        closeConnectionQuietly();
    }

    private void closeConnectionQuietly() {
        if (currentConnection != null) {
            try {
                currentConnection.close();
            } catch (SQLException ignore) {
                // best-effort
            }
            currentConnection = null;
        }
    }
}
