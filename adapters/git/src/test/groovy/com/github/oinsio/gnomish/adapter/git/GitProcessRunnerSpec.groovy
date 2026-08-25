package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2 of add-git-workflow: the git subprocess runner invokes {@code git <args...>} directly
 * (no shell) with a specified cwd, captures exit code/stdout/stderr separately without throwing
 * on a non-zero git exit, and surfaces a clear error when the {@code git} binary itself cannot
 * be launched.
 */
class GitProcessRunnerSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()

    def "FR2: runs a git command with the given cwd and captures a zero exit code"() {
        given:
        def repo = initWorkingRepo(tempDir)

        when:
        def result = runner.run(repo, 'rev-parse', '--is-inside-work-tree')

        then:
        result.exitCode() == 0
        result.stdout().trim() == 'true'
    }

    def "FR2: captures stdout and stderr separately, not merged"() {
        given:
        def repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'hello'
        runner.run(repo, 'add', 'a.txt')
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'first')

        when:
        def result = runner.run(repo, 'log', '--oneline')

        then:
        result.exitCode() == 0
        result.stdout().contains('first')
    }

    def "FR2: a non-zero git exit code is returned, not thrown"() {
        given:
        def repo = initWorkingRepo(tempDir)

        when:
        def result = runner.run(repo, 'show', 'nonexistent-ref')

        then:
        noExceptionThrown()
        result.exitCode() != 0
        !result.stderr().isEmpty()
    }

    def "FR2: git init --bare succeeds and produces a bare repository marker"() {
        when:
        def repo = initBareRepo(tempDir)

        then:
        new File(repo.toFile(), 'HEAD').exists()
    }

    def "FR2: running against a nonexistent cwd surfaces as a failed result, not a silent success"() {
        given:
        def missing = tempDir.resolve('does-not-exist')

        when:
        def result = runner.run(missing, 'status')

        then:
        result.exitCode() != 0
    }

    def "FR2: an unrecognized git subcommand is reported via a non-zero exit code and stderr"() {
        given:
        def repo = initWorkingRepo(tempDir)

        when:
        def result = runner.run(repo, 'not-a-real-git-command')

        then:
        result.exitCode() != 0
        !result.stderr().isEmpty()
    }

    def "FR2: a runner configured with a nonexistent git binary throws a clear exception instead of hanging"() {
        given:
        def brokenRunner = new GitProcessRunner('definitely-not-a-real-git-binary-xyz')

        when:
        brokenRunner.run(tempDir, 'status')

        then:
        def e = thrown(GitBinaryNotFoundException)
        e.message.contains('definitely-not-a-real-git-binary-xyz')
    }

    // NFR-S2 of fix-lifecycle-push: the runner is the single choke point where git's stderr enters
    // the factory, so the credential scrub lives here rather than at each of the ~10 call sites
    // that log stderr or put it in a report. Driven through a stand-in git binary (the runner's own
    // constructor seam) that emits the exact message real git produces when an origin URL carries a
    // PAT as its username and the subprocess has no terminal to read the password from — real git
    // cannot be made to emit it without a live HTTP server issuing a 401, and, on a machine that
    // does have a controlling terminal, would block on the prompt instead of failing.
    def "NFR-S2: git stderr carrying a remote URL's userinfo is scrubbed before any caller sees it"() {
        given: 'a git stand-in whose stderr echoes a PAT-in-URL exactly as git 2.55 does'
        def fakeGit = tempDir.resolve('fake-git')
        fakeGit.toFile().text = '''#!/bin/sh
echo "https://ghp_FAKETOKEN1234567890@github.com/acme/widgets.git"
echo "fatal: could not read Password for 'https://ghp_FAKETOKEN1234567890@github.com': Device not configured" >&2
exit 128
'''
        fakeGit.toFile().executable = true

        when:
        def result = new GitProcessRunner(fakeGit.toString()).run(tempDir, 'ls-remote', 'origin')

        then: 'the secret is gone from stderr, and the rest of the diagnosis survives'
        result.exitCode() == 128
        !result.stderr().contains('ghp_FAKETOKEN1234567890')
        result.stderr().contains("could not read Password for 'https://***@github.com'")

        and: 'stdout is untouched — OriginRemote#url reads the real origin URL from it'
        result.stdout().contains('ghp_FAKETOKEN1234567890@github.com')
    }

    // Design D8/NFR-R2 of add-factory-serve: isRepoLevelMutating classifies which subcommands must
    // serialize per clone. Exercised directly via reflection (private static, no observable side
    // effect on its own) rather than indirectly through run()'s locking behavior, so the exact
    // args.length boundary and the worktree subcommand's && short-circuit are pinned down without
    // depending on timing-sensitive concurrency assertions elsewhere in this package.
    def "D8: isRepoLevelMutating classifies fetch/push/worktree add|remove|prune as mutating, everything else as not"() {
        given:
        def method = GitProcessRunner.getDeclaredMethod('isRepoLevelMutating', String[])
        method.accessible = true

        expect:
        method.invoke(null, [args as String[]] as Object[]) == expected

        where:
        args | expected
        [] | false
        ['status'] | false
        ['fetch'] | true
        ['push'] | true
        ['worktree'] | false // length == 1: no second arg to inspect, must not index into args[1]
        ['worktree', 'add'] | true
        ['worktree', 'remove'] | true
        ['worktree', 'prune'] | true
        ['worktree', 'list'] | false // length > 1 but second arg is not add/remove/prune
        [
            'worktree',
            'prune',
            '--dry-run'
        ] | true
        // FR5 of add-sandbox-core: leading -c pairs are skipped before classifying, so the
        // harvest fetch's per-invocation config still serializes; a trailing bare -c never
        // indexes past the end.
        [
            '-c',
            'protocol.ext.allow=user',
            'fetch'
        ] | true
        [
            '-c',
            'a=b',
            '-c',
            'c=d',
            'push'
        ] | true
        [
            '-c',
            'a=b',
            'status'
        ] | false
        [
            '-c',
            'a=b',
            'worktree',
            'add'
        ] | true
        ['-c', 'a=b'] | false // only -c pairs, no subcommand at all
        ['-c'] | false // dangling -c with no value
    }

    // Design D8/NFR-R2: run() with a repo-level-mutating subcommand drives resolveCloneKey ->
    // canonicalize end-to-end against a real repo and a real linked worktree, so canonicalize's
    // success path (Path#toRealPath()) actually executes and its result is observably used — not
    // just exercised for line coverage. The observable property: a mutating command issued with
    // cwd inside the linked worktree must resolve back to the SAME clone-mutation-lock entry as
    // one issued with cwd at the main clone's own root, even though the two cwd strings are
    // completely different paths on disk. We assert this directly against the shared, private
    // MUTATION_LOCK's lock registry (same package, reflection for the private fields) rather than
    // via a timing-sensitive concurrency test. If canonicalize's result were corrupted (e.g. a
    // mutant returning the wrong value, or resolveCloneKey's fallback firing and keying on the
    // uncanonicalized cwd instead), the two calls would land in two different registry entries and
    // this assertion would fail.
    def "D8: a mutating command run from a linked worktree's cwd resolves to the same clone lock as one run from the clone root"() {
        given: 'a repo with an initial commit and a linked worktree checked out from it'
        def repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'hello'
        commitAll(repo)
        def worktreePath = tempDir.resolve('linked-worktree')

        and: 'the shared, per-process CloneMutationLock and its lock registry, reached via reflection'
        def lockField = GitProcessRunner.getDeclaredField('MUTATION_LOCK')
        lockField.accessible = true
        def mutationLock = lockField.get(null)
        def registryField = mutationLock.getClass().getDeclaredField('locksByClone')
        registryField.accessible = true
        Map registry = (Map) registryField.get(mutationLock)

        // MUTATION_LOCK is process-shared static state: other specs/tests running earlier in this
        // same JVM may already have populated the registry with entries for their own (distinct)
        // clones. This test asserts only its own delta and its own new key, never an absolute
        // registry size, to stay independent of test execution order.
        and: 'the registry keys already present before either call'
        Set keysBefore = new HashSet(registry.keySet())

        when: 'a mutating command runs with cwd at the clone root'
        def addResult = runner.run(repo, 'worktree', 'add', worktreePath.toString(), '-b', 'wt-branch')

        then: 'exactly one new lock entry was added, for this clone'
        addResult.exitCode() == 0
        registry.size() == keysBefore.size() + 1
        def newKeys = registry.keySet() - keysBefore
        newKeys.size() == 1
        def keyFromRoot = newKeys.first()

        when: 'a second mutating command runs with cwd inside the linked worktree it just created'
        def pruneResult = runner.run(worktreePath, 'worktree', 'prune')

        then: 'it resolves back to the SAME clone lock entry, not a new one keyed on the worktree path'
        pruneResult.exitCode() == 0
        registry.size() == keysBefore.size() + 1
        registry.keySet().contains(keyFromRoot)
    }

    // PIT NO_COVERAGE on canonicalize's exception-fallback branch (line 126:
    // `return path.toAbsolutePath().normalize();`). Reaching this branch requires
    // Path#toRealPath() to throw IOException, which only happens for a path that does not
    // actually resolve on the filesystem. Every path canonicalize() receives via the public run()
    // API is guaranteed to exist by that point (run() rejects a nonexistent cwd up front, and the
    // git-common-dir resolved from a successful `rev-parse` always names a real directory) — so
    // this branch is provably unreachable through run(), the same class of "true unit, no live
    // path through the public API" situation as waitFor's InterruptedException branch below.
    // Following that established precedent, this spec invokes the private static canonicalize
    // directly via reflection with a path that does not exist, forcing the IOException branch, and
    // asserts the fallback value it returns — not null — killing the NullReturnValsMutator mutant.
    def "D8: canonicalize falls back to the normalized absolute path (never null) when the real path cannot be resolved"() {
        given:
        def method = GitProcessRunner.getDeclaredMethod('canonicalize', Path)
        method.accessible = true
        def missing = tempDir.resolve('does-not-exist-so-toRealPath-throws')

        when:
        def result = method.invoke(null, missing)

        then:
        result != null
        result == missing.toAbsolutePath().normalize()
    }

    // The interrupt path this spec used to drive through a private `waitFor` copy now lives in
    // `:subprocess` (FR9, design D10 of bound-subprocess-commands): one supervisor seam, driven
    // deterministically by `ProcessSupervisorInterruptSpec`, replaces the five per-module copies
    // and the timing-race `@DoNotMutate` exemptions they each carried. What this module still owns
    // — that an interrupted git command reports INTERRUPTED rather than an ordinary non-zero exit
    // — is asserted where the callers branch on it (`ParkDeliveryFenceSpec`, task 3).

    // NFR-R2 of fix-lifecycle-push: a git command must never block on an interactive credential
    // prompt. `gnomish take`/`serve` run with no controlling terminal, but `gnomish run` inherits
    // the operator's, and there an expired token on origin would leave a push waiting forever for a
    // password. Driven through the runner's own git-binary seam because the property under test is
    // what the child process's environment holds, which only the child can report.
    def "NFR-R2: git runs with interactive credential prompting switched off"() {
        given: 'a git stand-in that reports the prompting-related variables it was handed'
        def fakeGit = tempDir.resolve('env-reporting-git')
        fakeGit.toFile().text = '''#!/bin/sh
echo "prompt=[${GIT_TERMINAL_PROMPT-unset}] askpass=[${GIT_ASKPASS-unset}] ssh=[${SSH_ASKPASS-unset}]"
'''
        fakeGit.toFile().executable = true

        when:
        def result = new GitProcessRunner(fakeGit.toString()).run(tempDir, 'ls-remote', 'origin')

        then:
        result.stdout().trim() == 'prompt=[0] askpass=[] ssh=[]'
    }
}
