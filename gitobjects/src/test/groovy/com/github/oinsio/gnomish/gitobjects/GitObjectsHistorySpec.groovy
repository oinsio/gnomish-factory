package com.github.oinsio.gnomish.gitobjects

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1 of harden-task-branch-contract: the bare-objects answer to a history question — is a commit
 * carrying this message reachable from this tip? — so a reader with no working copy can tell a
 * delivered task branch from a live one.
 */
class GitObjectsHistorySpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    private GitObjects git
    private ObjectId base

    def setup() {
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        git = openGitObjects(bare, tempDir)
        base = git.resolveRef('refs/heads/base').get()
    }

    private ObjectId commitWith(String message, String ref = 'refs/heads/gnomish/t1', ObjectId parent = base) {
        git.commit(new CommitRequest(ref, Optional.ofNullable(git.resolveRef(ref).orElse(null)), parent,
                [
                    new TreeEdit.PutFile("note-${message.hashCode()}.txt", message.bytes)
                ], metadata(1_700_000_000L, message)))
    }

    def "a message not in history is not found"() {
        expect:
        !git.historyContains(base, 'gnomish: cleanup')
    }

    def "a message carried by a reachable commit is found"() {
        given:
        def tip = commitWith('gnomish: cleanup')

        expect:
        git.historyContains(tip, 'gnomish: cleanup')
    }

    // The search walks history rather than looking at the tip alone, so later commits do not hide it.
    def "a message under later commits is still found"() {
        given:
        def cleanup = commitWith('gnomish: cleanup')
        def later = commitWith('a human commit after cleanup', 'refs/heads/gnomish/t1', cleanup)

        expect:
        git.historyContains(later, 'gnomish: cleanup')
    }

    // The fragment is matched verbatim: nothing in a commit message is ever read as a pattern.
    def "the fragment is matched as a fixed string"() {
        given:
        def tip = commitWith('gnomish: cleanup')

        expect:
        !git.historyContains(tip, 'gnomish: cl.anup')
    }

    // FR13 of harden-task-branch-contract: a reader with no working copy reads a commit's whole
    //     message, subject and body, because the claim epoch rides in a trailer down in the body.
    def "the full message of a commit is readable, body included"() {
        given:
        def tip = commitWith('gnomish: round build#1\n\nGnomish-Claim-Epoch: 4711')

        expect:
        git.commitMessage(tip).get().contains('Gnomish-Claim-Epoch: 4711')
        git.commitMessage(tip).get().startsWith('gnomish: round build#1')
    }

    // FR13, NFR-R2: a commit that does not resolve is a missing message, never a thrown failure
    def "a commit that does not resolve has no message"() {
        expect:
        git.commitMessage(new ObjectId('0'.multiply(40))).isEmpty()
    }

    def "a blank fragment is rejected before touching git"() {
        when:
        git.historyContains(base, fragment)

        then:
        thrown(IllegalArgumentException)

        where:
        fragment << ['', '   ']
    }
}
