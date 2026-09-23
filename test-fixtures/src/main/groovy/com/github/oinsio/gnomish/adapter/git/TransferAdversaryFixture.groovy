package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gittransfer.GitTransfer
import com.github.oinsio.gnomish.gittransfer.TransferSource.SeedPath
import java.nio.file.Path

/**
 * The opt-in half of the adversarial git posture, for a spec whose subject is a transfer's identity
 * (FR12 of own-git-transfer-argv): {@link BareGitRepoFixture#addOrigin} leaves the clone behind
 * origin with a decoy tag, and promises origin's tree stays byte-for-byte what the spec seeded;
 * the two shapes here — a tag inside the fetched history, an initialized submodule whose remote
 * has advanced — add a ref and change the tree, so they are named requests beside {@link
 * BareGitRepoFixture#addConvergedOrigin} rather than a widening of the default. Together with the
 * committed global configuration of design D11 (recursion and prune on), they are the fixture the
 * requirement lists. The container half — a box standing in for a task container behind an
 * {@code ext::} command that counts its sessions, and a {@code .gitmodules} symbolic link planted
 * by plumbing — and the ref-diff reading every identity feature asserts on live here too.
 */
trait TransferAdversaryFixture implements BareGitRepoFixture {

    /**
     * An {@code ext::} transport URL onto {@code box}, in the harvest's own shape ({@code <command>
     * %S <path>}): the command is a script that appends each session's argv to {@code counter} and
     * then serves the repository with {@code git upload-pack}, standing in for {@code docker exec}
     * (FR12, NFR-P1 of own-git-transfer-argv) — so a spec can assert how many pack sessions a
     * transfer spent, which the repository's end state cannot show.
     *
     * @param parent the directory the script is written under; never null
     * @param box the repository the command serves; never null
     * @param counter the file each session appends its argv to; absent until the first session
     * @return the URL to build a {@code Container} source from
     */
    String boxUploadPackUrl(Path parent, Path box, Path counter) {
        Path script = parent.resolve("box-upload-pack-${System.nanoTime()}.sh")
        script.toFile().text = "#!/bin/sh\necho \"\$@\" >> \"${counter}\"\nexec git \"\${1#git-}\" \"\$2\"\n"
        script.toFile().executable = true
        "ext::${script} %S ${box}"
    }

    /**
     * Commits, on {@code repo}'s current branch, a tree in which {@code .gitmodules} is a symbolic
     * link — the shape git's object validation refuses as {@code gitmodulesSymlink}, and one
     * {@code git add} itself will not stage, so the tree is built by plumbing ({@code mktree}) the
     * way a box could. Moves the branch ref; leaves the working tree and index alone.
     *
     * @param repo the repository whose branch gains the commit; never null
     * @return the new tip
     */
    String plantGitmodulesSymlink(Path repo) {
        def runner = new GitProcessRunner()
        Path target = repo.resolveSibling("gitmodules-symlink-target-${System.nanoTime()}.txt")
        target.toFile().text = '/etc/passwd'
        String blob = gitOutput(repo, 'hash-object', '-w', target.toString())
        Path listing = repo.resolveSibling("gitmodules-symlink-tree-${System.nanoTime()}.txt")
        listing.toFile().text = gitOutput(repo, 'ls-tree', 'HEAD') + "\n120000 blob ${blob}\t.gitmodules\n"
        // mktree reads the listing on stdin, which the runner does not offer; both ends go through
        // files so nothing here captures a process stream (RawCaptureGateSpec).
        Path treeOut = repo.resolveSibling("gitmodules-symlink-tree-${System.nanoTime()}.out")
        int mktree = new ProcessBuilder('git', 'mktree')
                .directory(repo.toFile())
                .redirectInput(listing.toFile())
                .redirectOutput(treeOut.toFile())
                .redirectError(treeOut.toFile())
                .start()
                .waitFor()
        String tree = treeOut.toFile().text.trim()
        assert mktree == 0 && !tree.isBlank(): "git mktree failed: ${tree}"
        def commit = runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit-tree', tree, '-p', 'HEAD', '-m', 'symlinked .gitmodules')
        assert commit.exitCode() == 0: "git commit-tree failed: ${commit.stderr()}"
        String tip = commit.stdout().forParsing().trim()
        assert runner.run(repo, 'update-ref', "refs/heads/${currentBranch(repo)}", tip).exitCode() == 0
        tip
    }

    /**
     * The owner's seed clone value with {@code branch} in the placeholder's slot — what the seed
     * helper's script does with its first positional parameter, done factory-side so the identity
     * feature for the seed kind runs the owner's exact argv and environment through the runner
     * (FR7, FR12 of own-git-transfer-argv). Nothing else about the value changes.
     */
    static GitTransfer seedTransfer(Path source, Path destination, String branch) {
        GitTransfer owner = GitTransfer.clone(new SeedPath(source, destination))
        new GitTransfer(owner.argv().collect {
            it == GitTransfer.BRANCH_PARAMETER ? branch : it
        }, owner.environment())
    }

    /** Every ref of {@code gitDir} (a repository or a submodule's git directory) with its object name. */
    Map<String, String> refs(Path gitDir) {
        gitOutput(gitDir, 'for-each-ref', '--format=%(refname) %(objectname)')
                .readLines()
                .findAll { !it.isBlank() }
                .collectEntries {
                    def parts = it.split(' '); [(parts[0]): parts[1]]
                }
    }

    /** The refs whose presence or object name differs between {@code before} and {@code after}. */
    static Set<String> changedRefs(Map<String, String> before, Map<String, String> after) {
        (before.keySet() + after.keySet()).findAll {
            before[it] != after[it]
        } as Set
    }

    /**
     * Plants a tag at origin's tip of {@code repo}'s current branch — inside the history the next
     * fetch of that branch delivers — so a transfer that auto-follows tags writes {@code
     * refs/tags/<tagName>} into the clone (FR12 of own-git-transfer-argv). A named opt-in beside
     * {@link #addOrigin}, not part of its default posture: it adds a ref to origin, which the
     * divergence half promises not to. Made in a throwaway clone; the operator's clone is untouched.
     *
     * @param repo the clone whose origin gets the tag; never null
     * @param parent the directory the throwaway clone is created under; never null
     * @param tagName the tag to plant; must not already exist in {@code repo}
     */
    void tagInsideOriginHistory(Path repo, Path parent, String tagName) {
        def runner = new GitProcessRunner()
        String branch = currentBranch(repo)
        String origin = gitOutput(repo, 'remote', 'get-url', 'origin')
        Path tagging = parent.resolve("origin-tag-${tagName}-${System.nanoTime()}")
        seedClone(parent, origin, tagging, '--branch', branch, '--single-branch')
        assert runner.run(tagging, 'tag', tagName, 'HEAD').exitCode() == 0
        def push = runner.run(tagging, 'push', 'origin', "refs/tags/${tagName}")
        assert push.exitCode() == 0: "planting the origin tag failed: ${push.stderr()}"
    }

    /**
     * Gives {@code repo} an initialized submodule at {@code sub/}, then advances both the
     * submodule's own remote and origin's branch — origin's new tip references the newer submodule
     * commit — so a transfer that recurses into submodules (an operator's {@code
     * submodule.recurse=true}) contacts the submodule's remote and moves refs under {@code
     * .git/modules/sub} (FR12 of own-git-transfer-argv). A named opt-in beside {@link #addOrigin}:
     * it changes origin's tree, which the divergence half promises not to. Leaves {@code repo}'s
     * local branch, tracking ref and checkout at the commit that introduced the submodule, one
     * commit behind origin, so the adversarial posture holds.
     *
     * @param repo the clone, already wired to an origin by {@link #addOrigin}; never null
     * @param parent the directory the submodule's remote and the throwaway clones go under; never null
     * @return the submodule's git directory inside {@code repo} ({@code .git/modules/sub}), whose refs
     *     a transfer of {@code repo} must leave untouched
     */
    Path addAdvancedSubmodule(Path repo, Path parent) {
        def runner = new GitProcessRunner()
        String branch = currentBranch(repo)
        String origin = gitOutput(repo, 'remote', 'get-url', 'origin')
        Path subWork = initWorkingRepo(parent, "submodule-work-${System.nanoTime()}")
        commit(subWork, 'sub.txt', 'one')
        Path subOrigin = addConvergedOrigin(subWork, parent, "submodule-${System.nanoTime()}.git")
        String subFirst = currentHead(subWork)

        // Origin gains the submodule at its first commit, by plumbing: no clone of the submodule is
        // needed to record a gitlink.
        Path adding = parent.resolve("origin-submodule-add-${System.nanoTime()}")
        seedClone(parent, origin, adding, '--branch', branch, '--single-branch')
        recordSubmodule(adding, subOrigin, subFirst, 'add submodule')
        assert runner.run(adding, 'push', 'origin', "HEAD:refs/heads/${branch}").exitCode() == 0

        // The operator's clone moves to that commit and initializes the submodule for real.
        def fetch = fetchFromOrigin(repo, "refs/heads/${branch}:refs/remotes/origin/${branch}")
        assert fetch.exitCode() == 0: "fetching the submodule commit failed: ${fetch.stderr()}"
        assert runner.run(repo, 'reset', '--hard', "refs/remotes/origin/${branch}").exitCode() == 0
        seedSubmodules(repo)
        Path subGitDir = repo.resolve('.git/modules/sub')
        assert subGitDir.toFile().directory: "the submodule was not initialized under ${subGitDir}"

        // Both remotes advance: the submodule's own, and origin to a tip that references it.
        commit(subWork, 'sub.txt', 'two')
        assert runner.run(subWork, 'push', 'origin', "HEAD:refs/heads/${currentBranch(subWork)}").exitCode() == 0
        Path bumping = parent.resolve("origin-submodule-bump-${System.nanoTime()}")
        seedClone(parent, origin, bumping, '--branch', branch, '--single-branch')
        recordSubmodule(bumping, subOrigin, currentHead(subWork), 'bump submodule')
        assert runner.run(bumping, 'push', 'origin', "HEAD:refs/heads/${branch}").exitCode() == 0
        subGitDir
    }

    /** Records {@code sub/} as a gitlink at {@code subCommit} with its {@code .gitmodules} entry, and commits. */
    private void recordSubmodule(Path repo, Path subOrigin, String subCommit, String message) {
        def runner = new GitProcessRunner()
        new File(repo.toFile(), '.gitmodules').text = "[submodule \"sub\"]\n\tpath = sub\n\turl = ${subOrigin}\n"
        assert runner.run(repo, 'add', '.gitmodules').exitCode() == 0
        assert runner.run(repo, 'update-index', '--add', '--cacheinfo', "160000,${subCommit},sub").exitCode() == 0
        def result = runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', message)
        assert result.exitCode() == 0: "git commit failed: ${result.stderr()}"
    }
}
