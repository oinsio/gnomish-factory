package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.nio.file.Path

/**
 * Reusable Spock fixture: creates local bare git repositories under a caller-supplied temp
 * directory (no network, no GitHub) so git-plumbing specs (this task and 2.2-2.7, section 3)
 * can exercise real {@code git} subprocess behavior against a real repo.
 *
 * <p>Supports FR2 of add-git-workflow.
 */
trait BareGitRepoFixture {

    /**
     * Runs {@code git init --bare} in a new subdirectory of {@code parent} named {@code name}
     * and returns its path. Fails the test loudly (via {@link GitProcessRunner}'s error surface)
     * rather than silently if {@code git} itself is unavailable in the test environment.
     */
    Path initBareRepo(Path parent, String name = 'origin.git') {
        Path repo = parent.resolve(name)
        repo.toFile().mkdirs()
        def runner = new GitProcessRunner()
        def result = runner.run(repo, 'init', '--bare')
        assert result.exitCode() == 0: "git init --bare failed: ${result.stderr()}"
        repo
    }

    /**
     * Runs {@code git init} (a normal, non-bare repo) in a new subdirectory of {@code parent}
     * named {@code name} and returns its path — useful for specs that need a working tree
     * rather than a bare remote.
     */
    Path initWorkingRepo(Path parent, String name = 'work') {
        Path repo = parent.resolve(name)
        repo.toFile().mkdirs()
        def runner = new GitProcessRunner()
        def result = runner.run(repo, 'init')
        assert result.exitCode() == 0: "git init failed: ${result.stderr()}"
        repo
    }

    /**
     * Stages everything under {@code repo} and creates a commit with a fixed test identity,
     * asserting both steps succeed — the standard "seed an initial commit" step shared by specs
     * that need a working tree with history rather than an empty repo.
     */
    void commitAll(Path repo, String message = 'init') {
        def runner = new GitProcessRunner()
        assert runner.run(repo, 'add', '.').exitCode() == 0
        def result = runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', message)
        assert result.exitCode() == 0: "git commit failed: ${result.stderr()}"
    }

    /**
     * Writes {@code content} to {@code fileName} under {@code repo}, stages it and commits it with
     * a fixed test identity, using {@code fileName} itself as the commit message — the standard
     * "add one named file" step shared by specs that build up a working tree file by file rather
     * than seeding it wholesale via {@link #commitAll}.
     */
    void commit(Path repo, String fileName, String content) {
        def runner = new GitProcessRunner()
        new File(repo.toFile(), fileName).text = content
        assert runner.run(repo, 'add', fileName).exitCode() == 0
        def result = runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', fileName)
        assert result.exitCode() == 0: "git commit failed: ${result.stderr()}"
    }

    /** Registers {@code url} as remote {@code name} in {@code repo}, asserting success. */
    void addRemote(Path repo, String name, String url) {
        def result = new GitProcessRunner().run(repo, 'remote', 'add', name, url)
        assert result.exitCode() == 0: "git remote add failed: ${result.stderr()}"
    }

    /**
     * Wires a real {@code origin} remote for {@code repo} and then <b>diverges the clone from it</b>
     * — the adversarial default this project's git fixtures owe (`.claude/rules/testing.md`, "Git
     * fixtures are adversarial by default").
     *
     * <p>The wiring half: a fresh local bare repo under {@code parent}, {@code repo}'s current
     * branch pushed to it, and the bare repo's own {@code HEAD} symref pointed at that same branch
     * name — so {@code git ls-remote --symref origin HEAD} (the real default-branch discovery
     * {@code RemoteDefaultBranch} runs, FR5 of add-base-ref-resolution) names the branch that was
     * actually pushed, whatever {@code init.defaultBranch} happens to be configured to here.
     *
     * <p>The adversarial half (see {@link #divergeFromOrigin}): a clone whose local refs equal
     * origin's cannot see a resolution that took the wrong ref, so every take/serve spec would pass
     * whether the base was read from origin or from the clone's own {@code refs/heads}. After this
     * call the clone's local branch is one commit BEHIND origin and a local TAG of the same name
     * points at that stale commit — the two shapes that actually reproduced a task branch cut from
     * the wrong commit (FR15, NFR-S1). A bare-name resolution now fails an existing spec instead of
     * waiting for a regression spec someone thought to write.
     *
     * <p>A spec that genuinely needs the converged posture asks for it by name: {@link
     * #addConvergedOrigin}.
     *
     * @param repo the working repo whose current branch becomes origin's default; never null
     * @param parent the directory the new bare repo is created under; never null
     * @param originName the bare repo's directory name; defaults to {@code origin.git}
     * @return the bare repo's path
     */
    Path addOrigin(Path repo, Path parent, String originName = 'origin.git') {
        Path origin = addConvergedOrigin(repo, parent, originName)
        divergeFromOrigin(repo, parent)
        origin
    }

    /**
     * The wiring half of {@link #addOrigin} without the divergence — for a spec whose subject is
     * something other than base resolution and that reads the clone's own checkout as if it were
     * origin's tip. Naming it at the call site is the point: the converged posture is a deliberate
     * request, never the default a spec silently inherits.
     *
     * @param repo the working repo whose current branch becomes origin's default; never null
     * @param parent the directory the new bare repo is created under; never null
     * @param originName the bare repo's directory name; defaults to {@code origin.git}
     * @return the bare repo's path
     */
    Path addConvergedOrigin(Path repo, Path parent, String originName = 'origin.git') {
        Path origin = initBareRepo(parent, originName)
        addRemote(repo, 'origin', origin.toString())
        String branch = currentBranch(repo)
        def runner = new GitProcessRunner()
        def push = runner.run(repo, 'push', 'origin', "HEAD:refs/heads/${branch}")
        assert push.exitCode() == 0: "git push origin failed: ${push.stderr()}"
        def symref = runner.run(origin, 'symbolic-ref', 'HEAD', "refs/heads/${branch}")
        assert symref.exitCode() == 0: "git symbolic-ref failed in origin: ${symref.stderr()}"
        origin
    }

    /**
     * Moves origin's branch one commit ahead of {@code repo}'s local branch of the same name, and
     * plants a local tag of that name on the stale commit.
     *
     * <p>The advance is an <b>empty</b> commit, made in a throwaway clone: origin's tree stays
     * byte-for-byte what the spec seeded, so nothing about the law, the pipeline definition or any
     * file assertion changes — only which commit the base name resolves to. That is exactly the
     * distinction a bare-name resolution gets wrong and a resolution through the refreshed
     * remote-tracking ref gets right.
     *
     * <p>The tag is git's own preference trap: an unqualified name resolves to {@code refs/tags/}
     * before {@code refs/heads/} (gitrevisions), so a planted tag silently wins any {@code
     * rev-parse} of a bare base name.
     *
     * @param repo the clone to leave behind origin; never null
     * @param parent the directory the throwaway clone is created under; never null
     */
    void divergeFromOrigin(Path repo, Path parent) {
        def runner = new GitProcessRunner()
        String branch = currentBranch(repo)
        String origin = gitOutput(repo, 'remote', 'get-url', 'origin')
        Path advance = parent.resolve("origin-advance-${branch.replace('/', '-')}-${System.nanoTime()}")
        assert runner.run(parent, 'clone', origin, advance.toString()).exitCode() == 0
        assert runner.run(advance, '-c', 'user.email=a@b.c', '-c', 'user.name=a',
        'commit', '--allow-empty', '-m', 'origin moves ahead').exitCode() == 0
        def push = runner.run(advance, 'push', 'origin', "HEAD:refs/heads/${branch}")
        assert push.exitCode() == 0: "advancing origin failed: ${push.stderr()}"
        def tag = runner.run(repo, 'tag', branch, 'HEAD')
        assert tag.exitCode() == 0: "planting the decoy tag failed: ${tag.stderr()}"
    }

    /**
     * Pushes {@code repo}'s current branch to its already-configured {@code origin} (see {@link
     * #addOrigin}), asserting success — for a spec that commits MORE content into {@code repo}
     * after {@link #addOrigin} ran (e.g. a per-test {@code config.yaml}): a real take/serve startup
     * reads its definition from git objects at origin's refreshed tip (FR13, D14 of
     * add-base-ref-resolution), never from the clone's own checkout, so a later local-only commit
     * is invisible to it until this is called again.
     */
    void pushOrigin(Path repo) {
        String branch = currentBranch(repo)
        // --force because the adversarial default of addOrigin leaves origin one (empty) commit
        // ahead of this clone: the spec's intent here is "origin's tip is now what I just
        // committed", and a fixture is the one place where saying so outright is right.
        def push = new GitProcessRunner().run(repo, 'push', '--force', 'origin', "HEAD:refs/heads/${branch}")
        assert push.exitCode() == 0: "git push origin failed: ${push.stderr()}"
    }

    /**
     * The name of the branch {@code repo} has checked out.
     *
     * <p>Read from the symbolic ref rather than {@code rev-parse --abbrev-ref HEAD}: that command
     * shortens the name only as far as stays UNAMBIGUOUS, so once {@link #divergeFromOrigin} has
     * planted a decoy tag of the same name it answers {@code heads/main} instead of {@code main} —
     * and a fixture pushing to {@code refs/heads/heads/main} silently stops updating the branch
     * every spec reads its law from. The full symbolic ref has no such ambiguity.
     *
     * @param repo the repository to ask; never null
     * @return the checked-out branch's short name; never null
     */
    String currentBranch(Path repo) {
        gitOutput(repo, 'symbolic-ref', 'HEAD') - 'refs/heads/'
    }

    /**
     * The claim epoch stamped on {@code rev}'s commit message in {@code repo}, or {@code null} when
     * the commit carries no trailer — read straight out of {@code git log -1 --format=%B}, never
     * through an adapter reader, so the assertion survives the removal of any production-side parse
     * (task 3.2 of fix-claim-epoch-fence).
     *
     * <p>The single owner of the trailer's test-side read: the key spelling and the "first
     * {@code Gnomish-Claim-Epoch:} line of the message" semantics live here, not in each spec base
     * that asserts over them (`.claude/rules/manual-sync-pairs.md`, rule of three).
     *
     * @param repo the repository to read the commit from; never null
     * @param rev any revision {@code git log} accepts — a hash, a branch name; never null
     */
    ClaimEpoch stampOf(Path repo, String rev) {
        def matcher = gitOutput(repo, 'log', '-1', '--format=%B', rev) =~ /(?m)^Gnomish-Claim-Epoch: (\d+)$/
        matcher ? new ClaimEpoch(Long.parseLong(matcher[0][1] as String)) : null
    }

    /** {@code rev}'s commit subject in {@code repo} — the service message, without the trailers below it. */
    String subjectOf(Path repo, String rev) {
        gitOutput(repo, 'log', '-1', '--format=%s', rev).strip()
    }

    /**
     * The commit hashes {@code revRange} selects in {@code repo}, oldest first — with an exclusive
     * range ({@code <from>..<branch>}), one tenure's work.
     *
     * @param repo the repository to walk; never null
     * @param revRange any range {@code git log} accepts; never null
     */
    List<String> commitsIn(Path repo, String revRange) {
        gitOutput(repo, 'log', '--reverse', '--format=%H', revRange)
                .readLines()
                .findAll { !it.isBlank() }
    }

    /** Runs an arbitrary read-only {@code git} command in {@code repo} and returns trimmed stdout. */
    String gitOutput(Path repo, String... args) {
        def result = new GitProcessRunner().run(repo, args)
        assert result.exitCode() == 0: "git ${args.join(' ')} failed: ${result.stderr()}"
        result.stdout().trim()
    }

    /**
     * Runs an arbitrary {@code git} command in {@code repo} and returns its exit code, for specs
     * that assert success/failure explicitly rather than treat any non-zero exit as a hard test
     * error — the cross-module-safe entry point since {@link GitProcessRunner#run} is
     * package-private.
     */
    int gitExitCode(Path repo, String... args) {
        new GitProcessRunner().run(repo, args).exitCode()
    }

    /**
     * Writes an executable {@code git} stand-in that appends every invocation's argv to {@code log}
     * and then runs the real {@code git}, and returns its path — hand it to a
     * {@code GitProcessRunner} to observe what a code path SPENDS rather than only what it leaves
     * behind. How many remote round-trips a check costs is invisible in the repository's end state,
     * so a cost claim ("one refs read", "one push") can only be asserted over the argv log.
     */
    Path recordingGit(Path log) {
        Path script = log.resolveSibling("recording-git-${log.fileName}.sh")
        script.toFile().text = "#!/bin/sh\necho \"\$@\" >> \"${log}\"\nexec git \"\$@\"\n"
        script.toFile().executable = true
        script
    }

    /**
     * The leading subcommand of each invocation {@link #recordingGit} logged, in call order; empty
     * when the stand-in was never invoked at all. Leading {@code -c key=value} global options are
     * skipped exactly as {@code GitProcessRunner} skips them when classifying — the caller's own
     * per-invocation config and the stall-detection options the runner prefixes onto every network
     * command (FR4 of bound-subprocess-commands) are not what a call-count assertion is about.
     */
    List<String> recordedSubcommands(Path log) {
        log.toFile().exists() ? log.toFile().readLines().collect {
            subcommandOf(it)
        } : []
    }

    private String subcommandOf(String argvLine) {
        def argv = argvLine.split(' ') as List
        while (argv.size() > 1 && argv.first() == '-c') {
            argv = argv.drop(2)
        }
        argv.first()
    }

    /**
     * The stall-detection options {@code GitProcessRunner} prefixes onto every invocation that
     * reaches a remote (FR4, design D5 of bound-subprocess-commands), so a spec asserting a
     * command's exact argv can state the caller's half without restating the runner's.
     */
    List<String> stallDetectionArgv() {
        [
            '-c',
            'http.lowSpeedLimit=1000',
            '-c',
            'http.lowSpeedTime=60'
        ]
    }

    /**
     * Runs {@code git worktree add <worktreePath> -b <branch>} against {@code repo} and returns
     * {@code worktreePath} — the cross-module-safe entry point for specs that need a real
     * registered worktree, since {@link GitProcessRunner#run} is package-private.
     */
    Path addWorktree(Path repo, Path worktreePath, String branch) {
        def result = new GitProcessRunner().run(repo, 'worktree', 'add', worktreePath.toString(), '-b', branch)
        assert result.exitCode() == 0: "git worktree add failed: ${result.stderr()}"
        worktreePath
    }

    /**
     * Builds a {@link GitBaseRefs} over a real {@link GitProcessRunner} and a {@link
     * GitInfrastructureRetry} driven by virtual time — the standard subject construction shared
     * by every spec exercising {@code GitBaseRefs} (delegation, probe) so its retry wiring is not
     * hand-duplicated per spec.
     */
    GitBaseRefs newGitBaseRefs() {
        new GitBaseRefs(new GitProcessRunner(), VirtualTimeGitRetries.gitInfrastructure())
    }

    /**
     * Creates a task branch off {@code repo}'s current {@code HEAD} via a real {@link
     * TaskBranchCreator} and returns its branch name — the standard "give me a task branch to
     * work against" step shared by specs that only need the resulting branch name, not the
     * creation result itself.
     *
     * @param repo the clone the branch is cut in; never null
     * @param taskId the task identifier to derive the branch name from; never null
     * @return the created branch's name; never null
     */
    String createTaskBranch(Path repo, String taskId) {
        def result = new TaskBranchCreator(new GitProcessRunner()).createBranch(repo, taskId, TaskStart.commit(repo, 'HEAD'))
        (result as BranchCreationResult.Created).branchName()
    }

    /**
     * Builds the topology shared by every {@link BaseRefresh} spec: a work repo with {@code main}
     * and {@code develop} branches (one commit each), a fresh origin bare repo carrying both, and a
     * real clone of it. {@code beforePush} runs against {@code work} once both branches exist and
     * before the push, for a spec that also needs a tag or other ref seeded alongside {@code
     * develop}'s commit; anything it creates that also needs pushing goes in {@code extraRefs}.
     *
     * @return {@code [work, origin, clone]} paths, in that order
     */
    List<Path> initBaseRefTopology(Path tempDir, List<String> extraRefs = [], Closure beforePush = null) {
        Path work = initWorkingRepo(tempDir, 'work')
        gitOutput(work, 'checkout', '-b', 'main')
        commit(work, 'a.txt', 'one')
        gitOutput(work, 'checkout', '-b', 'develop')
        commit(work, 'b.txt', 'two')
        beforePush?.call(work)
        Path origin = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', origin.toString())
        List<String> pushArgs = [
            'push',
            'origin',
            'main',
            'develop'
        ]
        pushArgs.addAll(extraRefs)
        assert gitExitCode(work, pushArgs as String[]) == 0
        assert gitExitCode(tempDir, 'clone', origin.toString(), 'clone') == 0
        [
            work,
            origin,
            tempDir.resolve('clone')
        ]
    }
}
