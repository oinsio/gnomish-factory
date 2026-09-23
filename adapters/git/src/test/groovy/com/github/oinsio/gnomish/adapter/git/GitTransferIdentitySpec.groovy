package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gittransfer.GitTransfer
import com.github.oinsio.gnomish.gittransfer.Refspec
import com.github.oinsio.gnomish.gittransfer.TransferSource
import com.github.oinsio.gnomish.gittransfer.TransferSource.Container
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR12, UX1, M2 of own-git-transfer-argv — the identity claim of the transfer owner on real git:
 * the set of refs a transfer changes is exactly the one destination its refspec names, and {@code
 * FETCH_HEAD} is untouched. One feature per source kind, each on the adversarial fixture the
 * requirement lists — a tag pointing into the fetched history, a pre-existing tag of the same name
 * at another commit, an initialized submodule whose remote has advanced, and the operator's global
 * configuration enabling recursion and prune (the committed build-wide file of design D11, which
 * this spec plants nothing beside: {@link AdversarialGitConfig#assertInEffect} proves it is in force
 * before any assertion can pass vacuously).
 *
 * <p>Component specs prove each flag is emitted; only this spec proves the flags, together, defeat
 * the fixture — it is red against the argv without design D2's common set (recorded in task 3.3's
 * completion note) and green with it.
 */
class GitTransferIdentitySpec extends Specification implements TransferAdversaryFixture {

    private static final String BRANCH = 'gnomish/task-1'

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()

    def setup() {
        AdversarialGitConfig.assertInEffect(runner)
    }

    def "FR12 origin: a fetch from origin changes exactly the named tracking ref"() {
        given: "the operator's clone, one commit behind origin with a decoy tag under the branch's name"
        Path clone = initWorkingRepo(tempDir, 'clone')
        commit(clone, 'a.txt', 'first')
        addOrigin(clone, tempDir)
        String branch = currentBranch(clone)

        and: 'a submodule initialized in the clone, whose remote and whose gitlink on origin have both advanced'
        Path subGitDir = addAdvancedSubmodule(clone, tempDir)

        and: 'a tag inside the history the fetch will deliver, absent from the clone'
        tagInsideOriginHistory(clone, tempDir, 'inside')
        assert gitExitCode(clone, 'rev-parse', '--verify', '--quiet', 'refs/tags/inside') != 0

        and: "a remote-tracking ref origin does not have, which the operator's fetch.prune=true would delete"
        assert gitExitCode(clone, 'update-ref', 'refs/remotes/origin/stale', 'HEAD') == 0

        and: 'the refs of the clone and of its submodule, and origin\'s tip, before the transfer'
        Map<String, String> refsBefore = refs(clone)
        Map<String, String> subRefsBefore = refs(subGitDir)
        String originTip = gitOutput(clone, 'ls-remote', '--heads', 'origin', branch).split('\t')[0]
        assert refsBefore["refs/remotes/origin/${branch}"] != originTip
        assert !Files.exists(clone.resolve('.git/FETCH_HEAD'))

        when: 'the owner\'s Origin fetch, in the short-name source shape the task-branch locate uses'
        def result = runner.run(clone,
                GitTransfer.fetch(TransferSource.ORIGIN, new Refspec("${branch}:refs/remotes/origin/${branch}")))

        then:
        result.exitCode() == 0

        and: 'the diff of the clone\'s refs is exactly the named tracking ref, now at origin\'s tip'
        Map<String, String> refsAfter = refs(clone)
        changedRefs(refsBefore, refsAfter) == [
            "refs/remotes/origin/${branch}".toString()
        ] as Set
        refsAfter["refs/remotes/origin/${branch}"] == originTip

        and: 'so the tag inside the fetched history stayed out, and the stale tracking ref was not pruned'
        !refsAfter.containsKey('refs/tags/inside')
        refsAfter.containsKey('refs/remotes/origin/stale')

        and: 'the submodule\'s refs are unchanged and its remote was never fetched'
        refs(subGitDir) == subRefsBefore
        !Files.exists(subGitDir.resolve('FETCH_HEAD'))

        and: 'FETCH_HEAD is still absent'
        !Files.exists(clone.resolve('.git/FETCH_HEAD'))
    }

    // FR4, NFR-S2: the allowlist for origin omits ext, and git applies it after the operator's URL
    //     rewrite — so the committed insteadOf onto an ext:: command (design D11) is refused rather
    //     than run, whatever the operator's global file says.
    def "FR4 origin: an ext:: URL substituted by the operator's rewrite is refused, and its command never runs"() {
        given: "a clone whose origin URL is the sentinel host the operator's configuration rewrites onto ext::"
        Path clone = initWorkingRepo(tempDir, 'rewritten')
        commit(clone, 'a.txt', 'first')
        addOrigin(clone, tempDir)
        assert gitExitCode(clone, 'remote', 'set-url', 'origin', AdversarialGitConfig.REWRITE_SENTINEL + 'repo.git') == 0

        when:
        def result = runner.run(clone,
                GitTransfer.fetch(TransferSource.ORIGIN, new Refspec('refs/heads/main:refs/remotes/origin/main')))

        then: 'git refuses the transport by name'
        result.exitCode() != 0
        result.stderr().contains("transport 'ext' not allowed")

        and: 'the rewritten command never ran: its marker is absent from the working directory'
        !Files.exists(clone.resolve(AdversarialGitConfig.EXT_RAN_MARKER))
    }

    // NFR-S1, NFR-S3, NFR-P1: the box is an ext:: command standing in for docker exec (the argv is
    //     otherwise the harvest's), its refs and tags are the gnome's, and the operator's clone
    //     holds a same-name tag the fetch must neither follow nor clobber.
    def "FR12 container: a harvest from a box changes exactly the task branch, spends one session, and refuses a symlinked .gitmodules"() {
        given: "the operator's clone on main, with the task branch and a tag of the name the box will also use"
        Path clone = initWorkingRepo(tempDir, 'operator')
        commit(clone, 'a.txt', 'first')
        assert gitExitCode(clone, 'branch', BRANCH) == 0
        assert gitExitCode(clone, 'tag', 'inside', 'HEAD') == 0

        and: 'the box: a clone of it, with gnome commits on the task branch, the same-name tag moved onto them, a tag elsewhere'
        Path box = seedClone(tempDir, clone.toString(), tempDir.resolve('box'), '--branch', BRANCH, '--single-branch')
        commit(box, 'work.txt', 'gnome work')
        assert gitExitCode(box, 'tag', '-f', 'inside', 'HEAD') == 0
        assert gitExitCode(box, 'tag', 'only-in-box', 'HEAD') == 0
        assert gitExitCode(box, 'checkout', '-q', '-b', 'scratch') == 0
        commit(box, 'scratch.txt', 'not on the task branch')
        assert gitExitCode(box, 'tag', 'elsewhere', 'HEAD') == 0
        assert gitExitCode(box, 'checkout', '-q', BRANCH) == 0
        String boxTip = currentHead(box)

        and: 'the harvest transport onto the box, counting its sessions'
        Path sessions = tempDir.resolve('upload-pack-sessions.log')
        Container source = new Container(boxUploadPackUrl(tempDir, box, sessions))
        Map<String, String> refsBefore = refs(clone)
        assert !Files.exists(clone.resolve('.git/FETCH_HEAD'))

        when: "the owner's Container fetch, in the harvest's refspec shape"
        def result = runner.run(clone, GitTransfer.fetch(source, new Refspec("${BRANCH}:${BRANCH}")))

        then:
        result.exitCode() == 0

        and: 'the diff of the clone\'s refs is exactly the task branch, now at the box\'s tip'
        Map<String, String> refsAfter = refs(clone)
        changedRefs(refsBefore, refsAfter) == [
            "refs/heads/${BRANCH}".toString()
        ] as Set
        refsAfter["refs/heads/${BRANCH}"] == boxTip
        refsAfter['refs/tags/inside'] == refsBefore['refs/tags/inside']
        !refsAfter.containsKey('refs/tags/only-in-box')

        and: 'FETCH_HEAD is still absent, and the clone is not shallow'
        !Files.exists(clone.resolve('.git/FETCH_HEAD'))
        !Files.exists(clone.resolve('.git/shallow'))

        and: "the operator's credential helper was never consulted: its marker is absent"
        !Files.exists(clone.resolve(AdversarialGitConfig.CREDENTIAL_GET_MARKER))

        and: 'exactly one upload-pack session served the transfer'
        Files.readAllLines(sessions).size() == 1
        Files.readAllLines(sessions)[0].startsWith('git-upload-pack ')

        when: 'the box plants a .gitmodules that is a symbolic link on the task branch, and the harvest runs again'
        plantGitmodulesSymlink(box)
        def refused = runner.run(clone, GitTransfer.fetch(source, new Refspec("${BRANCH}:${BRANCH}")))

        then: 'git refuses it, the refusal parses to the validation message id, and the branch ref is unchanged'
        refused.exitCode() != 0
        FetchRefusal.parse(refused.stderr()).map {
            it.messageId().forLog()
        } == Optional.of('gitmodulesSymlink')
        refs(clone) == refsAfter
        !Files.exists(clone.resolve('.git/FETCH_HEAD'))
    }

    // FR7, NFR-S3: the seed clone runs over git's transport path, so the operator's tags stay out,
    //     every object is validated, and no helper of the operator's is consulted.
    def "FR12 seed: a seed clone holds exactly the branch and its tracking ref, no tag, and refuses a bad object"() {
        given: "the factory clone on the task branch, with a lightweight and an annotated tag on its tip"
        Path factory = initWorkingRepo(tempDir, 'factory')
        commit(factory, 'a.txt', 'first')
        assert gitExitCode(factory, 'checkout', '-q', '-b', BRANCH) == 0
        commit(factory, 'work.txt', 'task work')
        assert gitExitCode(factory, 'tag', 'light', 'HEAD') == 0
        assert gitExitCode(factory, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'tag', '-a', '-m', 'heavy', 'heavy', 'HEAD') == 0
        String tip = currentHead(factory)
        Path box = tempDir.resolve('box')

        when: "the owner's seed clone, with the real branch where the helper's script puts its first positional parameter"
        def result = runner.run(tempDir, seedTransfer(factory, box, BRANCH))

        then:
        result.exitCode() == 0

        and: 'the box holds the branch and its tracking ref at the tip, and neither tag'
        refs(box) == [
            ("refs/heads/${BRANCH}".toString()): tip,
            ("refs/remotes/origin/${BRANCH}".toString()): tip
        ]

        and: 'the box is not shallow, wrote no FETCH_HEAD, and the operator\'s credential helper was never consulted'
        !Files.exists(box.resolve('.git/shallow'))
        !Files.exists(box.resolve('.git/FETCH_HEAD'))
        !Files.exists(box.resolve(AdversarialGitConfig.CREDENTIAL_GET_MARKER))

        when: 'a .gitmodules symbolic link lands on the branch and a second box is seeded from it'
        plantGitmodulesSymlink(factory)
        def refused = runner.run(tempDir, seedTransfer(factory, tempDir.resolve('box-2'), BRANCH))

        then: 'the transport path validates the objects: the clone is refused, not copied'
        refused.exitCode() != 0
        FetchRefusal.parse(refused.stderr()).map {
            it.messageId().forLog()
        } == Optional.of('gitmodulesSymlink')
    }
}
