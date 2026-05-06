package com.serban.notify.inbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async executor configuration for inbox SSE event dispatch (Faz 23.3 PR-E.3
 * Codex iter-1 P1.3 absorb).
 *
 * <p>Bounded {@link ThreadPoolTaskExecutor} prevents:
 * <ul>
 *   <li>Default {@code SimpleAsyncTaskExecutor} unbounded thread spawning
 *       under burst load (denial-of-service via subscriber spam)</li>
 *   <li>Silent task drop — explicit {@code RejectedExecutionHandler} logs
 *       rejection rather than discarding events</li>
 * </ul>
 *
 * <p>Sizing rationale (test cluster baseline):
 * <ul>
 *   <li>core-pool-size: 2 — handles steady-state SSE event broadcast</li>
 *   <li>max-pool-size: 8 — burst up to ~8 concurrent listener threads</li>
 *   <li>queue-capacity: 100 — small buffer; on overflow {@code CallerRunsPolicy}
 *       runs the task synchronously on the caller (publisher) thread, applying
 *       backpressure rather than dropping events</li>
 *   <li>thread name prefix: {@code inbox-sse-} for log/metrics correlation</li>
 * </ul>
 *
 * <p>Out-of-order delivery caveat: multiple events for same (org, subscriber)
 * may execute on different worker threads. Since payload is absolute
 * unread count (NOT delta), out-of-order arrival shows the latest event's
 * value transiently — acceptable for badge UX. Strict ordering would
 * require per-key serialization (e.g., one-thread-per-key); deferred
 * unless observed correctness issue.
 */
@Configuration
public class InboxSseAsyncConfig {

    @Bean(name = "inboxSseExecutor")
    public ThreadPoolTaskExecutor inboxSseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("inbox-sse-");
        // Caller runs the task if queue full + max pool busy — backpressure
        // to publisher (preferable to silent drop or unbounded queue).
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
