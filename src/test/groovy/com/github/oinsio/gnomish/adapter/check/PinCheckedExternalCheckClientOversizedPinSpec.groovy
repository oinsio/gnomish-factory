package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.adapter.git.AttemptCommitRef
import com.github.oinsio.gnomish.adapter.workspace.AttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.gitobjects.CommitRequest
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.gitobjects.GitObjectsFixture
import com.github.oinsio.gnomish.gitobjects.ObjectId
import com.github.oinsio.gnomish.gitobjects.TreeEdit
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR16, NFR-C1 of add-sandbox-core: the oversized-pin fail-closed degradation of the pin-check
 * guard — a pinned definition file over the read cap on either side cannot be byte-compared, so
 * the poll degrades to CannotVerify (never a silent pass, never a bogus quality Fail) and the
 * adapter is not contacted. Runs on a real temp repository — no git mocking.
 */
class PinCheckedExternalCheckClientOversizedPinSpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    GitObjects gitObjects
    ObjectId baseTip

    def setup() {
        Path bare = seedBareRepo(tempDir, ['.github/workflows/ci.yml': "name: ci\n"])
        gitObjects = openGitObjects(bare, tempDir)
        baseTip = gitObjects.resolveRef('refs/heads/base').orElseThrow()
    }

    private static Workspace workspaceAt(ObjectId attempt) {
        def ref = new AttemptCommitRef()
        ref.record(attempt.hex())
        new AttemptCommitWorkspace(ref)
    }

    // FR16, NFR-C1: a pinned blob exceeding PIN_READ_CAP_BYTES is CannotVerify naming the path —
    // the pin cannot be evaluated, so no adapter contact happens either.
    def "a pinned file over the read cap degrades to CannotVerify and the adapter is not invoked"() {
        given: 'an attempt whose pinned workflow file is one byte over the comparison read cap'
        byte[] oversized = new byte[(int) PinCheckedExternalCheckClient.PIN_READ_CAP_BYTES + 1]
        def attempt = gitObjects.commit(new CommitRequest(
                'refs/heads/gnomish/task-1', Optional.empty(), baseTip,
                [
                    new TreeEdit.PutFile('.github/workflows/ci.yml', oversized)
                ], metadata()))
        def delegate = new RecordingClient()
        def guard = new PinCheckedExternalCheckClient(
                delegate, { c -> [c.checkId()] as Set }, gitObjects, 'refs/heads/base')
        def check = new VerifyCheck.External(
                '.github/workflows/ci.yml', Duration.ofSeconds(1), Duration.ofSeconds(5),
                VerifyCheck.TimeoutClass.QUALITY, [])

        when:
        def status = guard.poll(check, workspaceAt(attempt))

        then: 'fail-closed: CannotVerify, not a Fail finding and not a silent pass-through'
        status instanceof PollStatus.CannotVerify
        def cannotVerify = (PollStatus.CannotVerify) status
        cannotVerify.reason().contains('oversized')
        cannotVerify.details().contains('.github/workflows/ci.yml')
        delegate.polls == 0
    }

    private static final class RecordingClient implements ExternalCheckClient {
        int polls = 0

        PollStatus poll(VerifyCheck.External check, Workspace workspace) {
            polls++
            new PollStatus.Pass()
        }
    }
}
