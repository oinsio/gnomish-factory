package com.github.oinsio.gnomish

import com.github.oinsio.gnomish.app.OrderedExit
import com.github.oinsio.gnomish.logtext.ShutdownPhase
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.boot.logging.LoggingSystem
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import spock.lang.Specification

/**
 * {@link CommandExit}: the shared exit path {@code run} / {@code take} / {@code dashboard} return
 * through — Spring's own shutdown hook off, the application context closed, and only then the
 * logging system stopped so the asynchronous FILE appender flushes what the command wrote.
 *
 * <p>FR9, NFR-R1 of harden-logging-observability.
 */
class CommandExitSpec extends Specification {

    @Configuration
    static class EmptyContext {}

    List<String> steps = []
    List<Thread> hooks = []

    def setup() {
        ShutdownPhase.reset()
    }

    def cleanup() {
        ShutdownPhase.reset()
        OrderedExit.install({}, {})
        hooks.clear()
        System.clearProperty(LoggingSystem.SYSTEM_PROPERTY)
    }

    // Spring's hook closes the context on a thread of its own, concurrently with a serve drain that
    // is still writing terminal lines. There is no framework-level way to order it after the drain,
    // so it is switched off and the factory owns the sequence outright.
    def "FR9: Spring's own shutdown hook is disabled"() {
        given:
        def application = application()

        when:
        CommandExit.start(application, new String[0], {
            steps << 'stopLogging'
        }, hooks.&add)

        then:
        !registerShutdownHookOf(application)
    }

    def "FR9: a normal exit closes the context, then stops logging"() {
        given:
        def application = application()
        ConfigurableApplicationContext context = null
        application.addInitializers({ ConfigurableApplicationContext it ->
            context = it
        })

        when:
        CommandExit.start(application, new String[0], {
            steps << 'stopLogging'
        }, hooks.&add)

        then: 'the context is live while the command runs'
        context.isActive()

        when:
        CommandExit.finish()

        then:
        !context.isActive()
        steps == ['stopLogging']
    }

    def "NFR-R1: a second finish changes nothing"() {
        given:
        def application = application()
        CommandExit.start(application, new String[0], {
            steps << 'stopLogging'
        }, hooks.&add)
        CommandExit.finish()

        when:
        CommandExit.finish()

        then:
        steps == ['stopLogging']
    }

    // The serve hook owns the sequence on a signal-initiated stop; `main` returning from its runner
    // mid-drain must not race it to the context close.
    def "FR9: finish defers once the shutdown phase has begun"() {
        given:
        def application = application()
        ConfigurableApplicationContext context = null
        application.addInitializers({ ConfigurableApplicationContext it ->
            context = it
        })
        CommandExit.start(application, new String[0], {
            steps << 'stopLogging'
        }, hooks.&add)

        when:
        ShutdownPhase.begin()
        CommandExit.finish()

        then:
        context.isActive()
        steps.isEmpty()

        cleanup:
        context.close()
    }

    // A run that dies before the context is even created still installed the exit path; the path
    // must find nothing to close rather than fail on the way out.
    def "FR9: a run that fails before the context exists still exits cleanly"() {
        given:
        def application = application()
        application.addListeners({ event ->
            if (event instanceof ApplicationEnvironmentPreparedEvent) {
                throw new IllegalStateException('configuration is broken')
            }
        } as ApplicationListener)

        when:
        CommandExit.start(application, new String[0], {
            steps << 'stopLogging'
        }, hooks.&add)

        then:
        thrown(Exception)

        when:
        CommandExit.finish()

        then:
        noExceptionThrown()
        steps == ['stopLogging']
    }

    // Switching Spring's hook off would otherwise cost `gnomish run` its Ctrl-C coverage: the tail
    // of the log file would stay in the async appender's queue and never reach disk.
    def "FR9: a signal hook is registered that runs the same sequence"() {
        given:
        def application = application()
        ConfigurableApplicationContext context = null
        application.addInitializers({ ConfigurableApplicationContext it ->
            context = it
        })

        when:
        CommandExit.start(application, new String[0], {
            steps << 'stopLogging'
        }, hooks.&add)

        then:
        hooks.size() == 1
        hooks[0].name == CommandExit.SIGNAL_HOOK_THREAD_NAME

        when:
        hooks[0].run()

        then:
        !context.isActive()
        steps == ['stopLogging']
    }

    // Two JVM shutdown hooks run concurrently; a serve drain and a context close must not.
    def "FR9: the signal hook stands down for a command that owns its own stop"() {
        given:
        def application = application()
        ConfigurableApplicationContext context = null
        application.addInitializers({ ConfigurableApplicationContext it ->
            context = it
        })
        CommandExit.start(application, new String[0], {
            steps << 'stopLogging'
        }, hooks.&add)

        when: 'the running command registers a hook of its own, as serve does'
        OrderedExit.reserveSignalOwner()
        hooks[0].run()

        then:
        context.isActive()
        steps.isEmpty()

        cleanup:
        context.close()
    }

    def "FR9: the logging stop runs the active logging system's shutdown handler"() {
        given:
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, RecordingLoggingSystem.name)
        RecordingLoggingSystem.stopped = false

        when:
        CommandExit.stopLogging()

        then:
        RecordingLoggingSystem.stopped
    }

    def "a logging system with no teardown of its own leaves nothing to flush"() {
        given: 'the no-op system Spring selects when logging is switched off entirely'
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE)

        when:
        CommandExit.stopLogging()

        then:
        noExceptionThrown()
    }

    private static SpringApplication application() {
        def application = new SpringApplication(EmptyContext)
        application.setBannerMode(Banner.Mode.OFF)
        application
    }

    /**
     * Spring keeps the flag in an internal properties holder with no public reader, so the
     * assertion goes through reflection rather than through a behavioural probe: the alternative —
     * letting the framework's hook actually run — would close this JVM's context and stop its
     * logging system, which is precisely what the production code exists to prevent.
     */
    private static boolean registerShutdownHookOf(SpringApplication application) {
        def propertiesField = SpringApplication.getDeclaredField('properties')
        propertiesField.setAccessible(true)
        def properties = propertiesField.get(application)
        def field = properties.getClass().getDeclaredField('registerShutdownHook')
        field.setAccessible(true)
        field.get(properties)
    }
}
