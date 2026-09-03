package com.github.oinsio.gnomish;

import com.github.oinsio.gnomish.sandbox.BindingProperties;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Application bootstrap: boots a plain headless Spring context and registers the
 * {@code @ConfigurationProperties} records of the layer modules by name (design D4).
 * {@code WebApplicationType.NONE} is not set
 * explicitly: with {@code spring-boot-starter} alone the classpath is the headless set, so
 * Spring Boot infers NONE — an explicit setting would duplicate what the dependency set already
 * guarantees (and the context-level specs pin it: {@code AnnotationConfigApplicationContext},
 * server-capability classes unresolvable).
 *
 * <p>This class is the documented PIT/mutation-gate exclusion (design D5, D10): it must stay
 * {@code main()} wiring only, with no logic that would deserve mutation coverage — which is why
 * the ordered teardown it hands the application to (Spring's own shutdown hook off, context close
 * before logging stop; design D6 of harden-logging-observability) lives in {@link CommandExit} and
 * not here.
 *
 * <p>It once also carried a single unconditional {@code log.debug} between {@link
 * CommandExit#start} and {@link CommandExit#finish}, as the production exercise of the
 * SLF4J-to-Logback stack and of FR4's "log level from configuration". That role has moved
 * (task 4.1 of harden-logging-observability): the level-override contract is now carried by the
 * {@code ${GNOMISH_LOG_LEVEL}} root-level substitution asserted in {@code LogbackConfigSpec}, and
 * the finer-grain {@code logging.level.*} leg by {@code LoggingLevelSpec}. What every boot now
 * writes instead is the {@code serve} lifecycle anchor set ({@code AnchorLog}, FR2) — real
 * operator content rather than a line whose only reader was a spec.
 *
 * <p>Implements FR2, FR4 of add-project-skeleton.
 */
@SpringBootApplication(scanBasePackages = FactoryApplication.USE_CASE_PACKAGE)
@EnableConfigurationProperties({
    FactoryProperties.class,
    ServeProperties.class,
    SandboxProperties.class,
    BindingProperties.class
})
public class FactoryApplication {

    /**
     * The single component-scan root (task 4.8, design D3): the {@code app} package, which this
     * module and {@code :application} share. Every {@code @Component} / {@code @Configuration} of
     * the composition root and the use-case layer lives below it, and no adapter package does — so
     * the scan cannot reach an adapter module however the classpath is laid out. Adapters state
     * what they contribute instead, as {@code @AutoConfiguration} entries in their own {@code
     * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
     *
     * <p>The typed configuration records are registered the same way — by name, above — rather than
     * by a {@code @ConfigurationPropertiesScan}, whose package sweep is recursive and would reach
     * across every module on the flat classpath.
     */
    static final String USE_CASE_PACKAGE = "com.github.oinsio.gnomish.app";

    static void main(String[] args) {
        CommandExit.start(new SpringApplication(FactoryApplication.class), args);
        CommandExit.finish();
    }
}
