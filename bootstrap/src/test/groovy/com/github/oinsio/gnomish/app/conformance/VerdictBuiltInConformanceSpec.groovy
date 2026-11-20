package com.github.oinsio.gnomish.app.conformance

import com.github.oinsio.gnomish.adapter.check.FilesExistExecutor
import com.github.oinsio.gnomish.adapter.check.ShellExecutor
import com.github.oinsio.gnomish.adapter.check.http.HttpExecutor
import com.github.oinsio.gnomish.adapter.scm.actions.GithubActionsExecutor
import com.github.oinsio.gnomish.app.BuiltInEntriesFixture
import com.github.oinsio.gnomish.app.DescribedLaws
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR8, FR13 of migrate-verify-to-executor-contract (design D18, "The conformance kit drives a
 * built-in through its entry"): the in-process verdict built-ins run through the conformance kit of
 * define-executor-contract by their registry entries — the entry's describe, and each case's
 * executor bound by the entry to that case's own working copy — carrying the settings of their
 * ordinary cases, which the settings-schema case judges too.
 */
class VerdictBuiltInConformanceSpec extends Specification {

    @TempDir
    Path scratch

    List<Duration> waits = []

    private KitReport run(VerifyCheck.Executor check, Map<String, ?> settings, Closure prepare = { Path p -> }) {
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
            'i.md', [check], new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
        def entry = BuiltInEntriesFixture.of(new VirtualClock()).of(definition, DescribedLaws.none()).find {
            it.name().value() == check.executorName()
        }
        def binding = { KitCase kase, Path workingCopy ->
            prepare(workingCopy)
            entry.executor(check, CheckRef.of(0, check), new DirectoryWorkspace(workingCopy))
        } as CaseBinding
        new ConformanceKit([:], { Duration d -> waits << d } as Sleeper)
        .run(new BuiltInCandidate(entry.describe(), binding, settings, scratch))
    }

    private static Map<KitCase, CaseVerdict> notApplicable(KitReport report) {
        report.verdicts().findAll {
            it.value instanceof CaseVerdict.NotApplicable
        }
    }

    // Scenario: executor/conformance-kit / files-exist with its declared files passes the verdict case
    def "FR8, FR13: files-exist with its declared files passes the settings-schema and verdict cases, each run in its own working copy"() {
        when:
        def report = run(VerifyChecks.builtin('files', [files: ['present.txt']]), [files: ['present.txt']]) { Path workingCopy ->
            Files.createFile(workingCopy.resolve('present.txt'))
        }
        def verdicts = report.verdicts()

        then: 'no case failed; the settings-schema and verdict cases passed'
        report.failures() == 0
        verdicts[KitCase.SETTINGS_SCHEMA] instanceof CaseVerdict.Passed
        verdicts[KitCase.VERDICT] instanceof CaseVerdict.Passed
        verdicts[KitCase.UNKNOWN_ROLE] instanceof CaseVerdict.Passed

        and: 'no case is reported not applicable for its settings, only for the wire, the roles and the poll'
        notApplicable(report).values().every {
            !(it as CaseVerdict.NotApplicable).reason().contains('settings')
        }
        notApplicable(report).keySet() == (KitCase.values().findAll {
            it.wireOnly()
        } +
        [
            KitCase.TRANSFORM,
            KitCase.DECISION,
            KitCase.MALFORMED_ANSWER,
            KitCase.PENDING_POLL,
            KitCase.PENDING_REPOLL
        ]) as Set

        and: 'the verdict case judged the path in its own working copy'
        Files.exists(scratch.resolve('verdict').resolve('present.txt'))
        waits == []
    }

    def "FR8: files-exist without its declared files is refused by the settings-schema case, and no run reaches it"() {
        when:
        def report = run(VerifyChecks.builtin('files', [files: ['present.txt']]), [:])

        then:
        report.verdicts()[KitCase.SETTINGS_SCHEMA] instanceof CaseVerdict.Failed
        (report.verdicts()[KitCase.VERDICT] as CaseVerdict.NotApplicable).reason().contains('settings schema refuses')
    }

    def "FR8, FR13: the shell built-in passes every applicable case through its entry, its command run in each case's working copy"() {
        when:
        def report = run(VerifyChecks.command('true'), [:])

        then:
        report.failures() == 0
        report.verdicts()[KitCase.VERDICT] instanceof CaseVerdict.Passed
        report.verdicts()[KitCase.SETTINGS_SCHEMA] instanceof CaseVerdict.Passed
        report.verdicts()[KitCase.UNKNOWN_ROLE] instanceof CaseVerdict.Passed
        waits == []
    }

    def "FR16 (D17): the #name describe the kit judges #carries checkIdIsPinnedPath, and the describe schema accepts it"() {
        given:
        def candidate = new BuiltInCandidate(describe, { KitCase kase, Path workingCopy ->
            throw new AssertionError('describing binds no case')
        } as CaseBinding, [:], scratch)

        when:
        def described = candidate.describe()

        then:
        described.violations() == []
        described.document().has('checkIdIsPinnedPath') == pinned
        described.document().path('checkIdIsPinnedPath').asBoolean(false) == pinned

        where:
        name | describe || pinned
        'github-actions' | GithubActionsExecutor.describeAnswer() || true
        'files-exist' | FilesExistExecutor.describeAnswer() || false
        'shell' | ShellExecutor.describeAnswer() || false
        'http' | HttpExecutor.describeAnswer() || false
        carries = pinned ? 'carries' : 'omits'
    }
}
