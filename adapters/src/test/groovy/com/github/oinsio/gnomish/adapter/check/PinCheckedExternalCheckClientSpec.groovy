package com.github.oinsio.gnomish.adapter.check

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.check.ExternalCheckPinContributor
import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.gitobjects.CommitRequest
import com.github.oinsio.gnomish.gitobjects.ObjectId
import com.github.oinsio.gnomish.gitobjects.TreeEdit
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR16, D10 of add-sandbox-core: the pin-check guard byte-compares the pin set — law-declared
 * paths united with the adapter contribution — against the base branch as bare git objects at
 * the attempt commit, before any adapter contact: a diff is a quality Fail with the diff as
 * findings and the delegate is never invoked; an empty union passes vacuously; fail-closed
 * degradations (no attempt commit in the workspace, unresolvable base) are CannotVerify.
 * Runs on real temp repositories — no git mocking.
 */
class PinCheckedExternalCheckClientSpec extends Specification implements PinCheckFixture {

    @TempDir
    Path tempDir

    def setup() {
        seedPinnedRepo(tempDir)
    }

    private ObjectId attemptCommitWith(List<TreeEdit> edits) {
        gitObjects.commit(new CommitRequest(
                        'refs/heads/gnomish/task-1', Optional.empty(), baseTip, edits, metadata()))
    }

    private ScriptedExternalCheckClient delegate = new ScriptedExternalCheckClient([new PollStatus.Pass()])

    private PinCheckedExternalCheckClient guard(ExternalCheckPinContributor contributor) {
        new PinCheckedExternalCheckClient(delegate, contributor, gitObjects, 'refs/heads/base')
    }

    def "untouched pins pass and the poll goes through to the delegate"() {
        given: 'an attempt that changes only unpinned content'
        def attempt = attemptCommitWith([
            new TreeEdit.PutFile('src/Main.java', bytes('code'))
        ])

        when:
        def status = guard({ c ->
            [c.checkId()] as Set
        }).poll(check([]), workspaceAt(attempt))

        then:
        status == new PollStatus.Pass()
        delegate.pollCount == 1
    }

    def "a rewritten adapter-contributed definition file fails before the adapter is invoked"() {
        given: 'the "Rewritten workflow is caught before the adapter is invoked" scenario'
        def attempt = attemptCommitWith([
            new TreeEdit.PutFile('.github/workflows/ci.yml', bytes("name: ci\non: [push]\nsteps: echo ok\n"))
        ])

        and:
        def logs = LogCaptureSupport.attach(PinCheckedExternalCheckClient)

        when:
        def status = guard({ c ->
            [c.checkId()] as Set
        }).poll(check([]), workspaceAt(attempt))

        then:
        status instanceof PollStatus.Fail
        def fail = (PollStatus.Fail) status
        fail.findings().size() == 1
        fail.findings()[0].message().contains(".github/workflows/ci.yml")
        fail.findings()[0].message().contains('differs from the base branch')
        fail.findings()[0].location() == '.github/workflows/ci.yml'

        and: 'the adapter was never invoked'
        delegate.pollCount == 0

        and: 'FR15 of harden-logging-observability: the refusal to invoke is a coded WARN naming the check'
        def warned = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.EXTERNAL_CHECK_PIN_MISMATCH.head())
        }
        warned != null
        warned.level == Level.WARN
        warned.formattedMessage.contains('ci')

        cleanup:
        logs.detach()
    }

    def "a modified law-declared pin path fails even when the adapter file is untouched"() {
        given:
        def attempt = attemptCommitWith([
            new TreeEdit.PutFile('config/analyzer.yml', bytes('rules: none'))
        ])

        when:
        def status = guard({ c ->
            [c.checkId()] as Set
        }).poll(check(['config/analyzer.yml']), workspaceAt(attempt))

        then:
        status instanceof PollStatus.Fail
        ((PollStatus.Fail) status).findings()*.location() == ['config/analyzer.yml']
        delegate.pollCount == 0
    }

    def "a pinned path deleted on the gnome branch is a diff"() {
        given:
        def attempt = attemptCommitWith([
            new TreeEdit.DeletePath('config/analyzer.yml')
        ])

        when:
        def status = guard(ExternalCheckPinContributor.none())
                .poll(check(['config/analyzer.yml']), workspaceAt(attempt))

        then:
        status instanceof PollStatus.Fail
        ((PollStatus.Fail) status).findings()[0].message().contains('was removed relative to the base branch')
        delegate.pollCount == 0
    }

    def "a pinned path that exists only on the gnome branch is a diff"() {
        given:
        def attempt = attemptCommitWith([
            new TreeEdit.PutFile('config/extra.yml', bytes('planted'))
        ])

        when:
        def status = guard(ExternalCheckPinContributor.none()).poll(check(['config/extra.yml']), workspaceAt(attempt))

        then:
        status instanceof PollStatus.Fail
        ((PollStatus.Fail) status).findings()[0].message().contains('is absent from the base branch')
        delegate.pollCount == 0
    }

    def "a pinned path absent from both sides is byte-identical vacuously"() {
        given:
        def attempt = attemptCommitWith([
            new TreeEdit.PutFile('src/Main.java', bytes('code'))
        ])

        when:
        def status = guard(ExternalCheckPinContributor.none())
                .poll(check(['config/never-existed.yml']), workspaceAt(attempt))

        then:
        status == new PollStatus.Pass()
        delegate.pollCount == 1
    }

    def "an empty union passes vacuously without touching git"() {
        given: 'the "Interactive client with nothing declared passes the pin" scenario'
        def workspace = AttemptCommitWorkspaces.empty()

        when: 'the workspace carries no attempt commit at all — vacuous pass must not need one'
        def status = guard(ExternalCheckPinContributor.none()).poll(check([]), workspace)

        then:
        status == new PollStatus.Pass()
        delegate.pollCount == 1
    }

    def "early substitution is caught at the point of use against the base branch"() {
        given: 'a definition rewritten at an earlier stage: several commits separate it from the tip'
        def stage1 = attemptCommitWith([
            new TreeEdit.PutFile('.github/workflows/ci.yml', bytes('name: weakened'))
        ])
        def stage3Tip = gitObjects.commit(new CommitRequest(
                        'refs/heads/gnomish/task-1', Optional.of(stage1), stage1,
                        [
                            new TreeEdit.PutFile('src/Main.java', bytes('honest work'))
                        ], metadata(1_700_000_100L)))

        when: 'the check is used two stages later, its own file untouched since the substitution'
        def status = guard({ c ->
            [c.checkId()] as Set
        }).poll(check([]), workspaceAt(stage3Tip))

        then: 'the comparison is against the base branch, not the previous round, so the diff is caught'
        status instanceof PollStatus.Fail
        ((PollStatus.Fail) status).findings()[0].location() == '.github/workflows/ci.yml'
        delegate.pollCount == 0
    }

    def "a non-empty pin set with a workspace carrying no attempt commit is CannotVerify"() {
        when:
        def status = guard({ c -> [c.checkId()] as Set })
        .poll(check([]), new FakeWorkspace())

        then:
        status instanceof PollStatus.CannotVerify
        ((PollStatus.CannotVerify) status).reason().contains('attempt-commit workspace')
        delegate.pollCount == 0
    }

    def "an unresolvable base ref is CannotVerify, never a silent pass"() {
        given:
        def attempt = attemptCommitWith([
            new TreeEdit.PutFile('src/Main.java', bytes('code'))
        ])
        def brokenGuard = new PinCheckedExternalCheckClient(
                delegate, { c ->
                    [c.checkId()] as Set
                }, gitObjects, 'refs/heads/no-such-branch')

        when:
        def status = brokenGuard.poll(check([]), workspaceAt(attempt))

        then:
        status instanceof PollStatus.CannotVerify
        ((PollStatus.CannotVerify) status).reason().contains('base branch')
        delegate.pollCount == 0
    }

    def "law-declared and adapter-contributed paths are one union — the same path is compared once"() {
        given: 'the workflow file is both law-declared and adapter-contributed, and differs'
        def attempt = attemptCommitWith([
            new TreeEdit.PutFile('.github/workflows/ci.yml', bytes('name: changed'))
        ])

        when:
        def status = guard({ c -> [c.checkId()] as Set })
        .poll(check(['.github/workflows/ci.yml']), workspaceAt(attempt))

        then: 'exactly one finding, not two'
        ((PollStatus.Fail) status).findings().size() == 1
    }

    private static byte[] bytes(String text) {
        text.getBytes(StandardCharsets.UTF_8)
    }
}
