package com.github.oinsio.gnomish

import com.github.oinsio.gnomish.app.OperatorHomeFixture
import com.github.oinsio.gnomish.app.OrderedExit
import com.github.oinsio.gnomish.logtext.ShutdownPhase
import java.nio.file.Path
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link CommandExit} installs the shutdown step its generic signal hook runs (FR21, design D19 of
 * fix-terminal-receipt-convergence, fourth amendment): a {@code run} or {@code take} interrupted from
 * the terminal kills its process tree and sweeps every live group while the context is still up,
 * and only then closes it. The production step, {@code RealProcessTreeKiller}, is driven on real
 * processes by {@code ShutdownSweepSpec}; here the hook is seamed so the test JVM's children live.
 */
class CommandExitShutdownStepSpec extends Specification {

    @TempDir
    Path tmp

    OperatorHomeFixture operatorHome

    @Configuration
    static class EmptyContext {}

    List<String> steps = []
    List<Thread> hooks = []

    def setup() {
        ShutdownPhase.reset()
        operatorHome = OperatorHomeFixture.install(tmp.resolve('home'))
    }

    def cleanup() {
        operatorHome.close()
        ShutdownPhase.reset()
        OrderedExit.install({}, {}, {})
    }

    def "FR21: the signal hook runs the shutdown step while the context is up, then closes it"() {
        given:
        def application = new SpringApplication(EmptyContext)
        application.setBannerMode(Banner.Mode.OFF)
        ConfigurableApplicationContext context = null
        application.addInitializers({ ConfigurableApplicationContext it ->
            context = it
        })
        CommandExit.start(application, new String[0], {
            steps << 'stopLogging'
        }, {
            steps << "shutdownStep, context active=${context.isActive()}".toString()
        }, hooks.&add)

        when: 'the operator interrupts the command'
        hooks[0].run()

        then:
        steps == [
            'shutdownStep, context active=true',
            'stopLogging'
        ]
        !context.isActive()
    }
}
