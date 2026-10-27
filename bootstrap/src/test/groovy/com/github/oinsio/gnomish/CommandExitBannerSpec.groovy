package com.github.oinsio.gnomish

import com.github.oinsio.gnomish.app.OperatorHomeFixture
import com.github.oinsio.gnomish.app.OrderedExit
import com.github.oinsio.gnomish.logtext.ShutdownPhase
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.springframework.boot.SpringApplication
import org.springframework.context.annotation.Configuration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link CommandExit} switches Spring Boot's startup banner off for every command: stdout carries
 * the command's output, and under {@code --json} that output must be one JSON document (FR5 of
 * add-doctor-command, scenario "JSON for automation"). The application here is built with Spring's
 * default banner mode, as production builds it, and the banner is observed on {@code System.out}.
 * The packaged jar's stdout is asserted end to end by {@code DoctorJsonE2eSpec}.
 */
class CommandExitBannerSpec extends Specification {

    @TempDir
    Path tmp

    OperatorHomeFixture operatorHome

    @Configuration
    static class EmptyContext {}

    List<Thread> hooks = []

    def setup() {
        ShutdownPhase.reset()
        operatorHome = OperatorHomeFixture.install(tmp.resolve('home'))
    }

    def cleanup() {
        CommandExit.finish()
        operatorHome.close()
        ShutdownPhase.reset()
        OrderedExit.install({}, {}, {})
    }

    // FR5: a banner ahead of the JSON document would make stdout two things
    // Scenario: cli/operator-doctor / JSON for automation
    def "FR5: a command started through the exit path prints no Spring Boot banner"() {
        when:
        String stdout = stdoutOf(['project', 'list'] as String[])

        then:
        !stdout.contains('Spring Boot')
        !stdout.contains(':: ')
    }

    def "the operator's own banner-mode option still overrides the default"() {
        when:
        String stdout = stdoutOf([
            'project',
            'list',
            '--spring.main.banner-mode=console'
        ] as String[])

        then: 'the switch is Spring\'s own, bound over the default rather than ignored'
        stdout.contains('Spring Boot')
    }

    private String stdoutOf(String[] args) {
        def application = new SpringApplication(EmptyContext)
        PrintStream original = System.out
        def buffer = new ByteArrayOutputStream()
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8))
        try {
            CommandExit.start(application, args, {}, {}, hooks.&add)
        } finally {
            System.setOut(original)
        }
        buffer.toString(StandardCharsets.UTF_8)
    }
}
