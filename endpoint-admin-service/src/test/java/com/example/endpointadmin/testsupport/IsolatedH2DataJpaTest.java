package com.example.endpointadmin.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.UUID;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * BE-021 PR #319 follow-up — Codex {@code 019e6fbc} iter-3 nitpick.
 *
 * <p>Module-wide replacement for the
 * {@code @DataJpaTest + @AutoConfigureTestDatabase(replace=NONE) + @ActiveProfiles("test")}
 * trio used by every H2 {@code @DataJpaTest} class in
 * {@code endpoint-admin-service}. Pins each distinct Spring context boot
 * under this annotation to its own UUID-named in-memory H2 instance via
 * an {@link ApplicationContextInitializer} declared on
 * {@link ContextConfiguration#initializers()}. Classes whose merged
 * context configurations are identical safely share one cached context
 * (and therefore one URL), because Spring never tears down a cached
 * context between cache-sharing classes.
 *
 * <h3>Why this exists</h3>
 *
 * <p>{@code application-test.yml} historically shipped a single shared
 * H2 URL ({@code jdbc:h2:mem:endpointadmin;DB_CLOSE_DELAY=-1}) — one
 * instance per JVM, shared by every {@code @DataJpaTest} class. When
 * any sibling class's cached {@code EntityManagerFactory} is evicted
 * (Spring's context cache LRU, Surefire fork-reuse, JVM exit) Hibernate
 * runs {@code ddl-auto=create-drop} and DROPS every table from the
 * shared instance. The next class that boots a context inherits the
 * now-empty database, because the cached EMF's schema-generation hook
 * fires only once at context init — never at first query. PR #317's
 * {@code @DirtiesContext(BEFORE_CLASS)} workaround was not sufficient
 * (CI red on main commits {@code 79a5ae82} and {@code 305561df}); PR
 * #319 isolated {@code HmacDeviceCredentialProviderTest} narrowly via
 * an inline {@code @DynamicPropertySource} but the root risk (any
 * sibling can drop the shared schema; future test reordering re-exposes
 * any new {@code @DataJpaTest} class) remained module-wide.
 *
 * <h3>What this does</h3>
 *
 * <p>Tagging a class with {@code @IsolatedH2DataJpaTest} replaces the
 * usual annotation trio. The bundled
 * {@link IsolatedH2Initializer} runs once per Spring application
 * context boot — i.e. per test class whose merged context
 * configuration is unique. It computes a fresh
 * {@code jdbc:h2:mem:endpointadmin-it-<uuid>;...} URL and applies it to
 * {@code spring.datasource.url} before the {@code DataSource} bean is
 * resolved. The H2 parameters mirror {@code application-test.yml}
 * verbatim ({@code MODE=PostgreSQL}, {@code DATABASE_TO_LOWER=TRUE},
 * {@code DEFAULT_NULL_ORDERING=HIGH}, {@code DB_CLOSE_DELAY=-1},
 * {@code DB_CLOSE_ON_EXIT=FALSE}) so behaviour is preserved.
 *
 * <p>Per-class isolation means: no other test class can drop this
 * class's schema regardless of execution order, Surefire {@code
 * forkCount}/{@code reuseForks} policy, or context-cache eviction.
 *
 * <h3>When NOT to use this</h3>
 *
 * <p>Postgres Testcontainers integration tests
 * ({@code *PostgresIntegrationTest}) already provide their own
 * isolation through {@code @DynamicPropertySource} that routes
 * {@code spring.datasource.url} at the container URL. Those classes
 * MUST keep using {@code @DataJpaTest} directly (plus
 * {@code @Testcontainers} + their own {@code @DynamicPropertySource})
 * — applying this annotation to them would override the PG URL with
 * an H2 URL and break the suite.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(initializers = IsolatedH2DataJpaTest.IsolatedH2Initializer.class)
public @interface IsolatedH2DataJpaTest {

    /**
     * Per-context initializer that overrides {@code spring.datasource.url}
     * with a fresh UUID-named H2 instance before the DataSource bean is
     * resolved. Spring instantiates a new {@code IsolatedH2Initializer}
     * per {@code ConfigurableApplicationContext.initialize()} call, so
     * each test class whose merged context configuration is unique gets
     * its own URL.
     */
    class IsolatedH2Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            String dbName = "endpointadmin-it-" + UUID.randomUUID();
            String url = "jdbc:h2:mem:" + dbName
                    + ";MODE=PostgreSQL"
                    + ";DATABASE_TO_LOWER=TRUE"
                    + ";DEFAULT_NULL_ORDERING=HIGH"
                    + ";DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE";
            TestPropertyValues.of("spring.datasource.url=" + url).applyTo(applicationContext);
        }
    }
}
