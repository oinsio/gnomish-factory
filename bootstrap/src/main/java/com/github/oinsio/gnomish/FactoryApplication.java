package com.github.oinsio.gnomish;

import com.github.oinsio.gnomish.sandbox.BindingProperties;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * {@code main()} + {@code SpringApplication.run} wiring only, with no logic that would deserve
 * mutation coverage. The single unconditional {@code log.debug} statement below respects that
 * budget: it is the production exercise of the SLF4J-to-Logback stack (FR4) and carries no
 * branching. It is placed <em>after</em> {@code run} deliberately — only then has Spring Boot's
 * {@code LoggingSystem} applied {@code logging.level.*} from configuration/environment; before
 * {@code run}, Logback still sits on its DEBUG-to-console bootstrap default, which would print the
 * line regardless of configuration and defeat the FR4 level-toggle contract.
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

    private static final Logger log = LoggerFactory.getLogger(FactoryApplication.class);

    static void main(String[] args) {
        SpringApplication.run(FactoryApplication.class, args);
        log.debug("Factory context booted; configured log levels are active");
    }
}
