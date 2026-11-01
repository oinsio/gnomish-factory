package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.domain.engine.DescribedExecutors
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification

/**
 * The describe preflight of a manual {@code run} (FR4, FR13, UX1 of define-executor-contract; design
 * D5, as revised 2026-10-31): a described law is handed on, and a law that could not be described
 * fails the invocation as an invalid law fails it — the located errors rendered for the author —
 * with a box kept stopped and nothing assembled.
 */
class ManualRunDescribeSpec extends Specification implements RunChainFakes {

    private static final LawBinding BINDING = LawBinding.workingTree(Path.of('/tmp/gnomish-clone'))
    private static final ConfigError ERROR = new ConfigError('config.yaml', 'executors.acme.program',
    "executor 'acme' cannot be described (configuration error): describe exited 3")

    def "FR13: a described law is the one the run assembles with"() {
        given:
        def described = new DescribedLaw(BINDING, [:])

        expect:
        ManualRunDescribe.described(describing(described), runOrder(completingPipeline()),
                new DescribeMedium.Host(Path.of('/tmp/gnomish-clone')), BINDING).is(described)
        described.binding().is(BINDING)
        described.executors() == DescribedExecutors.none()
    }

    def "FR4, UX1: a law that could not be described fails the run with the located errors, as an invalid law does"() {
        when:
        ManualRunDescribe.described(describing(new LawDescription.Refused([ERROR])), runOrder(completingPipeline()),
        new DescribeMedium.Host(Path.of('/tmp/gnomish-clone')), BINDING)

        then:
        def failed = thrown(PipelineLoadFailedException)
        failed.renderedErrors() == [ERROR.render().forConsole()]
    }

    def "FR4: a container run whose law could not be described keeps its box stopped and assembles nothing"() {
        given:
        def support = Mock(SandboxRunSupport)

        when:
        ContainerTerminalDrive.run(describing(new LawDescription.Refused([ERROR])), support, runOrder(completingPipeline()),
        new TaskContext('PROJ-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        TaskState.atStageStart('build'), BINDING, null)

        then:
        thrown(PipelineLoadFailedException)
        1 * support.keepStopped()
        0 * support.pieces(_)
    }

    /** An assembly that describes as told and assembles nothing: an assemble call would throw. */
    private static RunAssembly describing(LawDescription answer) {
        [describe: { order, medium, credentials, binding ->
                answer
            }] as RunAssembly
    }
}
