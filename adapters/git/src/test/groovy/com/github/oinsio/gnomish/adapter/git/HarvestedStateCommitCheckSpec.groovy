package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gitobjects.GitObjects
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR22 of add-sandbox-core; FR20 of migrate-verify-to-executor-contract (design D21): the harvest
 * boundary's parent-check and read-back over a pending parent — the round's last registered commit
 * is the only parent the state commit may have once a pending commit landed, and a harvested pending
 * commit is judged by the same parent-check and the read-back of its one file.
 */
class HarvestedStateCommitCheckSpec extends Specification implements BareGitRepoFixture {

    static final String STATE = '.gnomish-task/state.json'
    static final String TRACE = '.gnomish-task/attempts/implement/1/trace.jsonl'

    @TempDir
    Path tempDir

    Path repo
    HarvestedStateCommitCheck check
    String snapshot
    String pending
    String stateCommit

    def setup() {
        repo = initWorkingRepo(tempDir, 'clone')
        commit(repo, 'work.txt', 'gnome work')
        snapshot = gitOutput(repo, 'rev-parse', 'HEAD')
        Files.createDirectories(repo.resolve('.gnomish-task/attempts/implement/1'))
        Files.writeString(repo.resolve(STATE), '{"pending":"run-1"}')
        commitAll(repo, 'gnomish: pending implement#1 token')
        pending = gitOutput(repo, 'rev-parse', 'HEAD')
        Files.writeString(repo.resolve(STATE), '{"round":1}')
        Files.writeString(repo.resolve(TRACE), 'trace')
        commitAll(repo, 'gnomish: round implement#1')
        stateCommit = gitOutput(repo, 'rev-parse', 'HEAD')
        check = new HarvestedStateCommitCheck(
                GitObjects.open(repo.resolve('.git'), Files.createDirectories(tempDir.resolve('tmp'))))
    }

    private static byte[] bytes(String text) {
        text.getBytes(StandardCharsets.UTF_8)
    }

    // FR20: the state commit is judged against the round's last registered commit, its pending one
    def "FR20: a state commit on the round's last pending commit passes against that commit"() {
        when:
        check.verify('T-1', stateCommit, pending, TRACE, bytes('{"round":1}'), bytes('trace'))

        then:
        noExceptionThrown()
    }

    // FR20: the snapshot below a pending commit is no longer the state commit's permitted parent
    def "FR20: a state commit judged against the snapshot below a pending commit is refused naming the parent"() {
        when:
        check.verify('T-1', stateCommit, snapshot, TRACE, bytes('{"round":1}'), bytes('trace'))

        then:
        def refused = thrown(RoundBoundaryViolationException)
        refused.message.contains("harvested commit's parent ${pending} is not the round's last registered commit ${snapshot}")
    }

    // FR20: a harvested pending commit passes on its parent and its one file
    def "FR20: a pending commit on the registered commit carrying the bytes the factory wrote passes"() {
        when:
        check.verifyPending('T-1', pending, snapshot, bytes('{"pending":"run-1"}'))

        then:
        noExceptionThrown()
    }

    // FR20: either half of the pending judgement refuses
    def "FR20: a pending commit #defect is a boundary violation"() {
        when:
        check.verifyPending('T-1', pending, parent.call(this), bytes(written))

        then:
        def refused = thrown(RoundBoundaryViolationException)
        refused.message.contains(evidence)

        where:
        defect | parent | written || evidence
        'on another parent' | { s ->
            s.pending
        } | '{"pending":"run-1"}' || 'is not the round\'s last registered commit'
        'whose state.json differs' | { s ->
            s.snapshot
        } | '{"pending":"run-2"}' || 'differs from what the factory wrote'
    }

    // FR20: a merge attached in the box is refused for a pending commit too
    def "FR20: a pending commit with a second parent is refused"() {
        given:
        def tree = gitOutput(repo, 'rev-parse', pending + '^{tree}')
        def merged = gitOutput(repo, 'commit-tree', tree, '-p', snapshot, '-p', stateCommit, '-m', 'gnomish: pending')

        when:
        check.verifyPending('T-1', merged, snapshot, bytes('{"pending":"run-1"}'))

        then:
        def refused = thrown(RoundBoundaryViolationException)
        refused.message.contains('has a second parent')
    }
}
