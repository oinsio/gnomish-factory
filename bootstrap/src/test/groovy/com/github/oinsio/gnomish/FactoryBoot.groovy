package com.github.oinsio.gnomish

import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext

/**
 * Boots the real factory context in process the way {@code FactoryApplication.main} does: the
 * command line registered in the bootstrap registry by {@link CommandExit#registerArguments}, so
 * the configuration loader runs exactly as it does for the packaged jar (design D8, D9 of
 * add-project-registry). A spec pairs it with {@code OperatorHomeFixture}, which gives the loader
 * a home of the spec's own; a context booted any other way is refused at startup.
 *
 * <p>The empty command line is the no-op a spec boots with: it resolves no project and the runner
 * returns at once (FR12 of add-manual-run). Properties a spec needs — a logging level — go in as
 * Spring's default properties, not on the command line, which would make it a {@code run}.
 *
 * <p>The caller closes the returned context.
 */
final class FactoryBoot {

    private FactoryBoot() {}

    /** The factory application with {@code args} registered, not yet run. */
    static SpringApplication application(Map<String, Object> properties = [:], String... args) {
        def application = new SpringApplication(FactoryApplication)
        CommandExit.registerArguments(application, args)
        application.setDefaultProperties(properties)
        application
    }

    /** Boots the factory with {@code properties} as defaults and {@code args} as its command line. */
    static ConfigurableApplicationContext boot(Map<String, Object> properties = [:], String... args) {
        application(properties, args).run(args)
    }
}
