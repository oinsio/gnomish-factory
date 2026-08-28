package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.environment.ScriptedSandboxDocker
import java.nio.file.Path
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

/**
 * `run`'s startup sweep (FR6, FR7 of add-serve-sandbox-lifecycle): {@link
 * ContainerRunTermination#sweepOrphans} evaluates through the injected {@link
 * SandboxLifecyclePass} with a {@link LivenessVerdict.NoVerdict} — `run` holds no project-wide
 * claim listing — never through the retired name-snapshot sweeper.
 */
class ContainerRunTerminationSweepSpec extends Specification {

    @TempDir
    Path tempDir

    def docker = new ScriptedSandboxDocker()
    def sandbox = new SandboxProperties('gnomish/img', null, null, null, [], [], false, null, null, null, null)

    def "sweepOrphans evaluates through the injected sandbox lifecycle pass with NoVerdict liveness"() {
        given:
        def environments = docker.environments('k1', tempDir, sandbox, tempDir.resolve('guard'))
        List<List<Object>> calls = []
        SandboxLifecyclePass pass = { cloneDir, liveness ->
            calls << [cloneDir, liveness]
            'sweep: 1 checked-alive'
        }
        def support = new ContainerRunSupport(new GitProcessRunner(), tempDir, 'T-1', environments, [], pass, ClaimEpochSource.NONE)

        when:
        ContainerRunTermination.sweepOrphans(support)

        then:
        calls.size() == 1
        def (calledDir, verdict) = calls[0]
        calledDir == tempDir
        verdict instanceof LivenessVerdict.NoVerdict
    }

    def "sweepOrphans is a no-op through SandboxLifecyclePass.NONE"() {
        given:
        def environments = docker.environments('k1', tempDir, sandbox, tempDir.resolve('guard'))
        def support = new ContainerRunSupport(
                new GitProcessRunner(), tempDir, 'T-1', environments, [], SandboxLifecyclePass.NONE, ClaimEpochSource.NONE)

        when:
        ContainerRunTermination.sweepOrphans(support)

        then:
        noExceptionThrown()
    }

    def "the public sweepOrphans() override delegates to ContainerRunTermination"() {
        given:
        def environments = docker.environments('k1', tempDir, sandbox, tempDir.resolve('guard'))
        def calls = []
        SandboxLifecyclePass pass = { cloneDir, liveness ->
            calls << cloneDir
            ''
        }
        def support = new ContainerRunSupport(new GitProcessRunner(), tempDir, 'T-1', environments, [], pass, ClaimEpochSource.NONE)

        when:
        support.sweepOrphans()

        then:
        calls == [tempDir]
    }

    def "logs the sweep summary only when it is non-blank"() {
        given:
        def environments = docker.environments('k1', tempDir, sandbox, tempDir.resolve('guard'))
        SandboxLifecyclePass pass = { cloneDir, liveness -> summary }
        def support = new ContainerRunSupport(new GitProcessRunner(), tempDir, 'T-1', environments, [], pass, ClaimEpochSource.NONE)
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(ContainerRunTermination)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)

        when:
        ContainerRunTermination.sweepOrphans(support)

        then:
        appender.list.any {
            it.formattedMessage.contains('sweep')
        } == expectLogged

        cleanup:
        logbackLogger.detachAppender(appender)
        appender.stop()

        where:
        summary | expectLogged
        'sweep: 1 checked-alive' | true
        '' | false
    }
}
