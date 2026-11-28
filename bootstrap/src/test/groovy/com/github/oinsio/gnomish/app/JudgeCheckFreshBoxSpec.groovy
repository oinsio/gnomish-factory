package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.runner.AgentCheckBinding
import com.github.oinsio.gnomish.adapter.agent.runner.FakeDialect
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.app.port.check.FreshBoxSource
import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.JudgeChecks
import java.nio.file.Files
import java.nio.file.Path

/**
 * The box of a {@code judge} check's request (design D2 of extract-agent-round-runner; FR15 of
 * add-sandbox-core): in a run that has boxes the {@code claude} entry's check half materializes a
 * fresh box at the attempt commit for each request and releases it when the request ends, so two
 * judges never share one and none grades in the round's box.
 *
 * <p>FR4 of extract-agent-round-runner.
 */
class JudgeCheckFreshBoxSpec extends JudgeDispatchSpecBase {

    // D2, FR15 of add-sandbox-core: every judge request materializes its own box at the attempt commit,
    //     released when the request ends; the round's box, with its residue, grades nothing
    // Scenario: verification/verification-hardening / Two judge checks do not share a box
    // Scenario: verification/verification-hardening / Poisoned round box cannot grade itself
    def "two judge checks on one attempt commit each run in a fresh box of their own, never the round's"() {
        given: 'the round box left a PATH shim outside the working copy'
        def roundBox = Files.createDirectories(root.resolve('round'))
        Files.writeString(roundBox.resolve('claude'), '#shim')
        List<String> asked = []
        List<Path> released = []
        FreshBoxSource boxes = { String commit, List<String> egress ->
            asked << commit
            def dir = Files.createDirectories(root.resolve("fresh-${asked.size()}"))
            def box = new HostTaskExecutionEnvironment(dir, new VirtualClock(), ChildEnvAllowlist.none(), launch)
            box.materialize('gnomish/TASK-7', null)
            new CheckEnvironmentSource.Acquired() {
                        TaskExecutionEnvironment environment() {
                            box
                        }

                        void close() {
                            released << dir
                            box.dispose()
                        }
                    }
        } as FreshBoxSource
        def runners = runners(new FakeDialect())
        def law = PipelineLaw.ofContent(['first.md': CRITERIA, 'second.md': CRITERIA])
        def dispatch = dispatch(AgentCheckBinding.inFreshBoxes(runners, boxes), runners, law)
        verdict = '{"passed":true,"findings":[]}'
        def attempt = AttemptCommitWorkspaces.at('b' * 40)

        when:
        def first = run(dispatch, JudgeChecks.agent('first.md', 'opus'), attempt, 0)
        def second = run(dispatch, JudgeChecks.agent('second.md', 'opus'), attempt, 1)

        then: 'two boxes, each materialized at the attempt commit for its one request and released after it'
        asked == ['b' * 40, 'b' * 40]
        released == [
            root.resolve('fresh-1'),
            root.resolve('fresh-2')
        ]
        [first, second].every { derived(it) instanceof Verdict.Pass }

        and: 'each request ran in its own box, and none in the round box where the shim lives'
        agentLaunches*.directory() == [
            root.resolve('fresh-1').toFile(),
            root.resolve('fresh-2').toFile()
        ]
        !Files.exists(root.resolve('fresh-1').resolve('claude'))
    }
}
