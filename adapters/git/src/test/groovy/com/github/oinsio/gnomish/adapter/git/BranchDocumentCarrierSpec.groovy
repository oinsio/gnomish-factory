package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, FR10 of type-untrusted-text: a task-branch document is read at the tip and handed on as a
 * carrier — the tip reader yields the document's content, not an answer about it, so it carries
 * the value onward and is no parser — and only the document reader, a machine writer annotated as
 * an exit, takes the raw text out, minting every field it lifts as a branch-document value. Driven
 * on a real clone: the document is the one the host medium committed.
 */
class BranchDocumentCarrierSpec extends Specification implements BareGitRepoFixture, RegisteredBranchFixture {

    @TempDir
    Path tempDir

    Path cloneDir

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('a.txt'), 'first')
        commitAll(cloneDir)
        def registered = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
        new GitTaskRepository(new GitProcessRunner(), registered, ClaimEpochSource.NONE, new VirtualClock()).createTask(
                new TaskContext('PROJ-1', UntrustedText.tracker('Fix the thing'), UntrustedText.tracker('Body'), []),
                TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        registerTip(cloneDir, 'PROJ-1')
    }

    // FR10: the read at the tip is not a parse — the content stays a carrier up to the reader.
    // F104: scenario text aligned to what this test asserts
    // Scenario: core/untrusted-text / A document reader carries the carrier onward
    def "FR10: the tip reader yields the document as the carrier git's output arrived in, and is no parser"() {
        when:
        Optional<UntrustedText> document =
                RefTipSource.at(new GitProcessRunner(), cloneDir, 'PROJ-1', 'gnomish/PROJ-1').readAtRegister(EnvelopePaths.TASK_JSON_PATH)

        then: 'the content travels on as a carrier, under the family it was captured in'
        document.isPresent()
        document.get().provenance() == Provenance.SUBPROCESS
        document.get().contains('Fix the thing')

        and: 'the reader holds neither annotation: it takes no text out of the carrier'
        !RefTipSource.isAnnotationPresent(UntrustedParser)
        !RefTipSource.isAnnotationPresent(UntrustedExit)
    }

    // FR3, FR10: the document reader is the one place the raw text leaves, and what it lifts is a
    //     branch-document value, whatever family the content arrived in.
    // Scenario: core/untrusted-text / Reading a branch document is not parsing
    def "FR3: only the document reader, an exit, takes the raw text out, minting branch-document fields"() {
        given:
        def document = RefTipSource.at(new GitProcessRunner(), cloneDir, 'PROJ-1', 'gnomish/PROJ-1')
                .readAtRegister(EnvelopePaths.TASK_JSON_PATH).get()

        when:
        def record = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(document))

        then: 'the reader is a machine writer annotated as an exit, not a parser'
        TaskJsonMapper.isAnnotationPresent(UntrustedExit)
        !TaskJsonMapper.isAnnotationPresent(UntrustedParser)

        and: 'each field it lifts is minted as a branch-document value equal to what was written'
        record.context().title() == UntrustedText.tracker('Fix the thing')
        record.context().title().provenance() == Provenance.BRANCH_DOCUMENT
        record.context().body().provenance() == Provenance.BRANCH_DOCUMENT
    }
}
