package com.example.report.execution;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link RemoteExecutorProperties} into the Spring context so
 * {@code @ConfigurationProperties(prefix = "report.remote-executor")}
 * binds from {@code application.yml} (PR-D2.1c1).
 *
 * <p>Component scanning picks up {@link RemoteAllowlist},
 * {@link RemoteRequestNormalizer}, {@link RemoteResponseNormalizer},
 * and {@link RemoteReportExecutor} automatically via {@code @Component}.
 * Only the properties record needs explicit registration since records
 * are not picked up by component scanning.
 */
@Configuration
@EnableConfigurationProperties(RemoteExecutorProperties.class)
public class RemoteExecutorConfiguration {
}
