package com.example.report.execution;

import com.example.report.registry.ExecutionConfig;
import com.example.report.registry.ReportDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Remote-http executor for {@link ReportDefinition} entries with
 * {@link com.example.report.registry.ExecutionKind#REMOTE_HTTP}
 * (ADR-0015, PR-D2.1c1).
 *
 * <p>Delegates to another platform service over HTTP via the
 * configured {@link RemoteExecutorProperties#allowlist()} entry.
 * Propagates user JWT + tenant {@code X-Company-Id} header so that
 * downstream service evaluates user-level authz / audit.
 *
 * <h2>Security contract (Codex 019e8306 iter-2)</h2>
 *
 * <ul>
 *   <li>Allowlist enforcement: arbitrary URL execution YASAK. The
 *       caller's {@code (service, path)} tuple must exact-match a
 *       deployment-time allowlist entry.</li>
 *   <li>Auth propagation: incoming user JWT is forwarded as
 *       {@code Authorization: Bearer <token>}. Service-to-service
 *       tokens NOT used (would bypass downstream user-level authz).</li>
 *   <li>Tenant context: {@code X-Company-Id} forwarded (must be
 *       pre-validated by {@code CompanyHeaderScopeNarrower} at the
 *       controller layer).</li>
 *   <li>Timeout: 5s default (configurable via
 *       {@link RemoteExecutorProperties#timeout()}, hard cap 30s).</li>
 * </ul>
 *
 * <h2>Error mapping</h2>
 *
 * <ul>
 *   <li>HTTP 401 → {@link RemoteAuthException}</li>
 *   <li>HTTP 403 → {@link RemoteAuthzException}</li>
 *   <li>HTTP 5xx / timeout / network → {@link RemoteExecutionException}</li>
 *   <li>Malformed response shape →
 *       {@link RemoteExecutionException} ({@code downstreamStatus=null})</li>
 *   <li>(service, path) not in allowlist → {@link RemoteAllowlistException}</li>
 * </ul>
 *
 * <h2>Logging</h2>
 *
 * <p>Structured log includes {@code reportKey/service/path/status/elapsedMs}.
 * Raw response body, JWT token, and X-Company-Id values are NEVER logged.
 */
@Component
public class RemoteReportExecutor {

    private static final Logger log = LoggerFactory.getLogger(RemoteReportExecutor.class);
    private static final String COMPANY_HEADER = "X-Company-Id";

    private final WebClient webClient;
    private final RemoteAllowlist allowlist;
    private final RemoteRequestNormalizer requestNormalizer;
    private final RemoteResponseNormalizer responseNormalizer;
    private final Duration timeout;

    public RemoteReportExecutor(
            @Qualifier("plainWebClientBuilder") WebClient.Builder builder,
            RemoteAllowlist allowlist,
            RemoteRequestNormalizer requestNormalizer,
            RemoteResponseNormalizer responseNormalizer,
            RemoteExecutorProperties properties) {
        // Codex 019e8306 iter-2: 5s default response timeout (NOT 30s).
        // Per-request timeout is also enforced via Mono.timeout() below for
        // belt-and-suspenders coverage of slow downstream reads.
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)
                .responseTimeout(properties.timeout());
        this.webClient = builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        this.allowlist = allowlist;
        this.requestNormalizer = requestNormalizer;
        this.responseNormalizer = responseNormalizer;
        this.timeout = properties.timeout();
    }

    /**
     * Execute a remote-http report.
     *
     * <p>Caller (controller) responsibilities BEFORE invoking:
     * <ul>
     *   <li>Resolve {@link ReportDefinition} with
     *       {@code execution.kind() == REMOTE_HTTP}</li>
     *   <li>Build {@link RemoteReportRequest} from query params +
     *       SecurityContext JWT + validated company-id</li>
     *   <li>Reject grouped/pivot requests upstream (this method handles
     *       flat shape only)</li>
     * </ul>
     *
     * @param definition  the report definition (used for logging
     *                    {@code reportKey} + responseShape resolution)
     * @param request     normalized flat request DTO
     * @return            normalized result (rows + total)
     * @throws RemoteAllowlistException if (service, path) not in allowlist
     * @throws RemoteAuthException     downstream 401
     * @throws RemoteAuthzException    downstream 403
     * @throws RemoteExecutionException 5xx, timeout, malformed response
     * @throws IllegalArgumentException if execution kind != REMOTE_HTTP
     */
    public RemoteReportResult execute(ReportDefinition definition, RemoteReportRequest request) {
        if (definition == null) {
            throw new IllegalArgumentException("ReportDefinition must not be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("RemoteReportRequest must not be null");
        }
        if (!definition.isRemoteHttp()) {
            throw new IllegalArgumentException(
                    "RemoteReportExecutor invoked for non-remote-http report: "
                            + definition.key() + " (kind="
                            + (definition.execution() == null
                                    ? "null" : definition.execution().kind()) + ")");
        }
        ExecutionConfig execution = definition.execution();
        RemoteExecutorProperties.AllowlistEntry entry = allowlist.require(
                execution.service(), execution.path());

        long startMs = System.currentTimeMillis();
        try {
            JsonNode body = doExecute(entry, request);
            RemoteReportResult result = responseNormalizer.normalize(
                    execution.responseShape(), body, execution.service(), execution.path());
            log.info("remote-http report executed: reportKey={} service={} path={} rows={} total={} elapsedMs={}",
                    definition.key(), execution.service(), execution.path(),
                    result.rows().size(), result.total(),
                    System.currentTimeMillis() - startMs);
            return result;
        } catch (WebClientResponseException ex) {
            // Codex 019e8306 iter-2: structured status mapping
            HttpStatusCode status = ex.getStatusCode();
            log.warn("remote-http report failed: reportKey={} service={} path={} status={} elapsedMs={}",
                    definition.key(), execution.service(), execution.path(),
                    status.value(), System.currentTimeMillis() - startMs);
            if (status.value() == 401) {
                throw new RemoteAuthException(execution.service(), execution.path(), ex);
            }
            if (status.value() == 403) {
                throw new RemoteAuthzException(execution.service(), execution.path(), ex);
            }
            throw new RemoteExecutionException(
                    execution.service(), execution.path(),
                    status.value(),
                    "downstream returned " + status.value(), ex);
        } catch (RuntimeException ex) {
            log.warn("remote-http report error: reportKey={} service={} path={} elapsedMs={} error={}",
                    definition.key(), execution.service(), execution.path(),
                    System.currentTimeMillis() - startMs, ex.getClass().getSimpleName());
            // Rethrow Remote*Exception unchanged (normalizer / allowlist guards)
            if (ex instanceof RemoteAuthException || ex instanceof RemoteAuthzException
                    || ex instanceof RemoteExecutionException
                    || ex instanceof RemoteAllowlistException) {
                throw ex;
            }
            // Timeout / network / other → RemoteExecutionException
            throw new RemoteExecutionException(
                    execution.service(), execution.path(), null,
                    "remote-http execution failed: " + ex.getMessage(), ex);
        }
    }

    private JsonNode doExecute(
            RemoteExecutorProperties.AllowlistEntry entry, RemoteReportRequest request) {
        String uri = UriComponentsBuilder.fromHttpUrl(entry.baseUrl())
                .path(entry.path())
                .queryParams(requestNormalizer.toQueryParams(entry.requestShape(), request))
                .toUriString();

        WebClient.RequestHeadersSpec<?> spec = webClient.get().uri(uri);

        if (request.jwtToken() != null && !request.jwtToken().isBlank()) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + request.jwtToken());
        }
        if (request.companyId() != null && !request.companyId().isBlank()) {
            spec = spec.header(COMPANY_HEADER, request.companyId());
        }

        return spec
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .onErrorResume(java.util.concurrent.TimeoutException.class, ex -> Mono.error(
                        new RemoteExecutionException(
                                entry.service(), entry.path(), null,
                                "downstream timeout after " + timeout, ex)))
                .block();
    }
}
