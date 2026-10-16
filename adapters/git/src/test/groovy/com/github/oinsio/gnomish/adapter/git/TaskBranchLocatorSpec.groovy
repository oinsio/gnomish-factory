package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.branch.BranchShapeClassifier
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.subprocess.Termination
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, FR13 of add-git-workflow: local -> remote-tracking -> narrow fetch of exactly
 * {@code gnomish/<task>} -> "not found", shared verbatim by resume (4.6) and inspection (5.2).
 *
 * FR6 of harden-task-branch-contract: absence is a fact only origin can state, so a fetch that
 * failed for any other reason answers {@code Unavailable} — retried under the infrastructure
 * budget — instead of the "not found" that routed a duplicate branch into existence.
 */
class TaskBranchLocatorSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def locator = new TaskBranchLocator(runner, VirtualTimeGitRetries.gitInfrastructure())

    private Path initBareWithBranch(Path parent, String repoName, String branch, String fileName, String content) {
        def bare = initBareRepo(parent, repoName)
        def seed = initWorkingRepo(parent, repoName + '-seed')
        commit(seed, 'seed.txt', 'seed')
        addRemote(seed, 'origin', bare.toString())
        runner.run(seed, 'push', 'origin', 'HEAD:refs/heads/main')
        runner.run(seed, 'checkout', '-b', branch)
        commit(seed, fileName, content)
        runner.run(seed, 'push', 'origin', branch)
        bare
    }

    /**
     * Clones only {@code main} (single-branch) so task branches pushed to the bare remote are
     * genuinely absent from the clone's remote-tracking refs until the locator fetches them —
     * a plain {@code git clone} would fetch every branch upfront and defeat these specs' setup.
     */
    private Path cloneOf(Path bare, String name) {
        seedClone(tempDir, bare.toString(), tempDir.resolve(name), '--branch', 'main', '--single-branch')
    }

    def "FR8: branch present locally is found without any fetch, even when origin lacks it entirely"() {
        given: 'a clone with the task branch created locally, and origin pointed at an unrelated bare repo without it'
        def clone = initWorkingRepo(tempDir, 'clone-local')
        commit(clone, 'a.txt', 'first')
        def creator = new TaskBranchCreator(runner)
        creator.createBranch(clone, 'PROJ-1', TaskStart.commit(clone, 'HEAD'))
        def emptyOrigin = initBareRepo(tempDir, 'empty-origin.git')
        addRemote(clone, 'origin', emptyOrigin.toString())

        when:
        def location = locator.locate(clone, 'PROJ-1')

        then: 'found locally; the locator never needed to consult origin'
        location instanceof BranchLocation.Local
        (location as BranchLocation.Local).ref() == 'refs/heads/gnomish/PROJ-1'
    }

    // FR13 of tighten-spec-contract: the locate fetch is a touchpoint fetch — one transfer moves
    //     the branch and its factory register, the register straight into the clone's own.
    def "FR13: the locate fetch brings origin's factory register with the branch"() {
        given: 'origin carries the branch and its register'
        def bare = initBareWithBranch(tempDir, 'origin-reg.git', 'gnomish/PROJ-20', 'f.txt', 'hi')
        def seed = tempDir.resolve('origin-reg.git-seed')
        runner.run(seed, 'push', 'origin', 'HEAD:refs/gnomish-factory/PROJ-20')
        def clone = cloneOf(bare, 'clone-reg')

        when:
        def location = locator.locate(clone, 'PROJ-20')

        then:
        location instanceof BranchLocation.RemoteTracking
        gitOutput(clone, 'rev-parse', 'refs/gnomish-factory/PROJ-20') == gitOutput(seed, 'rev-parse', 'HEAD')
    }

    def "FR13: a branch origin holds no register for is still located, and the clone gets no register"() {
        given: 'origin carries the branch alone, as no factory closing push ever wrote it'
        def bare = initBareWithBranch(tempDir, 'origin-noreg.git', 'gnomish/PROJ-21', 'f.txt', 'hi')
        def clone = cloneOf(bare, 'clone-noreg')

        when:
        def location = locator.locate(clone, 'PROJ-21')

        then: 'the branch arrived through the branch-only fetch; no register was invented for it'
        location instanceof BranchLocation.RemoteTracking
        gitExitCode(clone, 'rev-parse', '--verify', '--quiet', 'refs/gnomish-factory/PROJ-21') != 0
    }

    // FR13 of tighten-spec-contract (D13, amended 2026-10-13): a tracking ref `git clone` left
    //     carries no register, so it does not end the locate — the touchpoint fetch still runs.
    // Scenario: git/git-task-persistence / A tracking ref without a local branch still fetches the register
    def "FR13: a tracking ref already present still runs the touchpoint fetch, which lands the register"() {
        given: 'origin carries the branch and its register; the clone holds an older tracking ref and no register'
        def bare = initBareWithBranch(tempDir, 'origin-tracked.git', 'gnomish/PROJ-22', 'f.txt', 'hi')
        def seed = tempDir.resolve('origin-tracked.git-seed')
        def clone = cloneOf(bare, 'clone-tracked')
        fetchFromOrigin(clone, 'refs/heads/gnomish/PROJ-22:refs/remotes/origin/gnomish/PROJ-22')
        commit(seed, 'g.txt', 'closing')
        runner.run(seed, 'push', 'origin', 'gnomish/PROJ-22', 'HEAD:refs/gnomish-factory/PROJ-22')
        def logs = LogCaptureSupport.attach(TaskBranchLocator, Level.DEBUG)

        when:
        def location = locator.locate(clone, 'PROJ-22')

        then: 'the tracking ref was refreshed and the register landed in the clone beside it'
        location == new BranchLocation.RemoteTracking('refs/remotes/origin/gnomish/PROJ-22')
        gitOutput(clone, 'rev-parse', 'refs/remotes/origin/gnomish/PROJ-22') == gitOutput(seed, 'rev-parse', 'HEAD')
        gitOutput(clone, 'rev-parse', 'refs/gnomish-factory/PROJ-22') == gitOutput(seed, 'rev-parse', 'HEAD')

        and: 'a delivered fetch is no fallback'
        logs.list.every {
            !it.formattedMessage.contains('falling back')
        }

        cleanup:
        logs?.detach()
    }

    // Scenario: git/git-task-persistence / An unreachable origin falls back to the tracking ref
    def "FR13: an unreachable origin falls back to the tracking ref already present, and lands no register"() {
        given: 'origin has the branch with a factory envelope, and the clone already fetched it once without its register'
        def bare = initBareWithBranch(tempDir, 'origin2.git', 'gnomish/PROJ-2', 'f.txt', 'hi')
        def seed = tempDir.resolve('origin2.git-seed')
        Files.createDirectories(seed.resolve('.gnomish-task'))
        commit(seed, '.gnomish-task/task.json', '{}')
        assert runner.run(seed, 'push', 'origin', 'gnomish/PROJ-2').exitCode() == 0
        def clone = cloneOf(bare, 'clone2')
        fetchFromOrigin(clone, 'refs/heads/gnomish/PROJ-2:refs/remotes/origin/gnomish/PROJ-2')
        def tracked = gitOutput(clone, 'rev-parse', 'refs/remotes/origin/gnomish/PROJ-2')

        and: 'origin is then unreachable, so the touchpoint fetch cannot deliver'
        runner.run(clone, 'remote', 'set-url', 'origin', tempDir.resolve('does-not-exist.git').toString())
        def logs = LogCaptureSupport.attach(TaskBranchLocator, Level.DEBUG)

        when:
        def location = locator.locate(clone, 'PROJ-2')

        then: 'the present tracking ref is the answer, unchanged, with no register invented'
        location == new BranchLocation.RemoteTracking('refs/remotes/origin/gnomish/PROJ-2')
        gitOutput(clone, 'rev-parse', 'refs/remotes/origin/gnomish/PROJ-2') == tracked
        gitExitCode(clone, 'rev-parse', '--verify', '--quiet', 'refs/gnomish-factory/PROJ-2') != 0

        and: 'read offline, the branch is Unregistered — the honest answer, never Unknown (FR10 of fix-terminal-receipt-convergence)'
        new BranchShapeClassifier().classify(new BranchTipFactsReader().read(
                        RefTipSource.at(runner, clone, 'PROJ-2', (location as BranchLocation.RemoteTracking).ref()))) ==
                new BranchShape.Unregistered('refs/gnomish-factory/PROJ-2', tracked)

        and: 'the fallback leaves a trace naming the branch and the ref it fell back to'
        logs.list.count {
            it.level == Level.DEBUG && it.formattedMessage.contains('gnomish/PROJ-2 did not deliver')
            && it.formattedMessage.contains('falling back to the tracking ref refs/remotes/origin/gnomish/PROJ-2')
        } == 1

        cleanup:
        logs?.detach()
    }

    def "FR13: a fetch cut off beside a present tracking ref falls back to it rather than trusting the ref's presence"() {
        given: 'a git whose tracking ref exists and whose fetch was cut off on its deadline'
        // The exit code is authoritative only on EXITED (GitCommandResult), so a zero carried by
        // a cut-off fetch must not count as delivery.
        def git = ScriptedGit.reftableClone()
                .answer(['fetch'], GitCommandResult.of(0, '', '', Termination.TIMED_OUT))
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    'refs/remotes/origin/gnomish/PROJ-14'
                ], 0, "${'2' * 40}\n")
                .answer(['rev-parse'], 1)
        def logs = LogCaptureSupport.attach(TaskBranchLocator, Level.DEBUG)

        when:
        def location = instantRetryLocator(git).locate(tempDir, 'PROJ-14')

        then: 'the fallback answered without asking origin again, and said so'
        location == new BranchLocation.RemoteTracking('refs/remotes/origin/gnomish/PROJ-14')
        git.calls(['ls-remote']).isEmpty()
        git.calls(['fetch']).size() == 1
        logs.list.count {
            it.formattedMessage.contains('falling back to the tracking ref')
        } == 1

        cleanup:
        logs?.detach()
    }

    def "FR8: branch only on origin is narrow-fetched and becomes readable via the returned ref"() {
        given:
        def bare = initBareWithBranch(tempDir, 'origin3.git', 'gnomish/PROJ-3', 'f.txt', 'branch-content')
        def clone = cloneOf(bare, 'clone3')
        assert runner.run(clone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/gnomish/PROJ-3').exitCode() != 0

        when:
        def location = locator.locate(clone, 'PROJ-3')

        then:
        location instanceof BranchLocation.RemoteTracking
        def ref = (location as BranchLocation.RemoteTracking).ref()
        ref == 'refs/remotes/origin/gnomish/PROJ-3'

        and: 'the resolved ref is readable end-to-end via git show'
        def show = runner.run(clone, 'show', "${ref}:f.txt")
        show.exitCode() == 0
        show.stdout().forParsing().trim() == 'branch-content'
    }

    def "FR8: the narrow fetch retrieves exactly the target branch, never a second unrelated branch on origin"() {
        given: 'origin has the target branch plus an unrelated second gnomish branch'
        def bare = initBareWithBranch(tempDir, 'origin4.git', 'gnomish/PROJ-4', 'f.txt', 'wanted')
        def seed = initWorkingRepo(tempDir, 'origin4-seed2')
        addRemote(seed, 'origin', bare.toString())
        fetchFromOrigin(seed, 'refs/heads/main:refs/remotes/origin/main')
        runner.run(seed, 'checkout', '-b', 'gnomish/OTHER', 'origin/main')
        commit(seed, 'other.txt', 'unrelated')
        runner.run(seed, 'push', 'origin', 'gnomish/OTHER')
        def clone = cloneOf(bare, 'clone4')

        when:
        locator.locate(clone, 'PROJ-4')

        then: 'only the target branch got a remote-tracking ref'
        runner.run(clone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/gnomish/PROJ-4').exitCode() == 0

        and: 'the unrelated branch was never fetched'
        runner.run(clone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/gnomish/OTHER').exitCode() != 0
    }

    // FR8 of add-git-workflow, ADR 0008: the locate fetch is a factory transfer like any other, so
    //     it is built by the owner (GitTransfer) and carries the same protective flags. Without them git
    //     auto-follows tags into the operator's own refs/tags and truncates FETCH_HEAD — two
    //     writes the factory promised never to make in a clone the operator also uses.
    def "FR8: the locate fetch writes neither an auto-followed tag nor FETCH_HEAD"() {
        given: "origin carries the task branch and a tag reachable only from it"
        def bare = initBareWithBranch(tempDir, 'origin-narrow.git', 'gnomish/PROJ-11', 'f.txt', 'wanted')
        def seed = tempDir.resolve('origin-narrow.git-seed')
        runner.run(seed, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'tag', 'v9.9')
        runner.run(seed, 'push', 'origin', 'v9.9')
        def clone = cloneOf(bare, 'clone-narrow')
        // The clone itself auto-follows the tag; dropping it locally is what leaves the locate
        // fetch as the only thing that could write refs/tags/ in this spec.
        assert gitExitCode(clone, 'tag', '-d', 'v9.9') == 0
        assert gitOutput(clone, 'for-each-ref', '--format=%(refname)', 'refs/tags').isEmpty()

        when:
        def location = locator.locate(clone, 'PROJ-11')

        then: 'the one named ref arrived'
        location instanceof BranchLocation.RemoteTracking

        and: "nothing else did — the operator's tags and FETCH_HEAD are untouched"
        gitOutput(clone, 'for-each-ref', '--format=%(refname)', 'refs/tags').isEmpty()
        !Files.exists(clone.resolve('.git/FETCH_HEAD'))
    }

    // Scenario: tracker/tracker-take / Confirmed-missing ref routes fresh
    def "FR13: branch absent everywhere is reported as not-found, not a crash"() {
        given: 'a clone pointed at a bare origin that has no branches at all'
        def bare = initBareRepo(tempDir, 'origin5.git')
        def clone = initWorkingRepo(tempDir, 'clone5')
        commit(clone, 'a.txt', 'first')
        addRemote(clone, 'origin', bare.toString())
        def logs = LogCaptureSupport.attach(TaskBranchLocator, Level.DEBUG)

        when:
        def location = locator.locate(clone, 'PROJ-5')

        then:
        noExceptionThrown()
        location instanceof BranchLocation.NotFound

        and: 'FR5: the failed fetch behind the absence is traced — a failure and a fact answer alike here'
        def traces = logs.list.findAll {
            it.formattedMessage.contains('origin confirms it is absent')
        }
        traces.size() == 1
        traces[0].level == Level.DEBUG
        traces[0].formattedMessage.contains('gnomish/PROJ-5')

        cleanup: 'the appender and the DEBUG pin are JVM-global, so they come off even on a failed assertion'
        logs.detach()
    }

    def "FR13: no origin configured at all is also reported as not-found, not a crash"() {
        given:
        def clone = initWorkingRepo(tempDir, 'clone-no-origin')
        commit(clone, 'a.txt', 'first')

        when:
        def location = locator.locate(clone, 'PROJ-6')

        then:
        noExceptionThrown()
        location instanceof BranchLocation.NotFound
    }

    def "FR8: branch name is sanitized via TaskIdSanitizer before locating"() {
        given:
        def clone = initWorkingRepo(tempDir, 'clone-sanitized')
        commit(clone, 'a.txt', 'first')
        new TaskBranchCreator(runner).createBranch(clone, 'PROJ 7: fix/it', TaskStart.commit(clone, 'HEAD'))

        when:
        def location = locator.locate(clone, 'PROJ 7: fix/it')

        then:
        location instanceof BranchLocation.Local
        (location as BranchLocation.Local).ref() == 'refs/heads/gnomish/PROJ-7-fix-it'
    }

    /**
     * A git over a clone without the tracking ref, whose origin confirms it carries the branch
     * (an {@code ls-remote} listing) and whose every fetch exits with {@code fetch}.
     */
    private static ScriptedGit originCarries(GitCommandResult fetch) {
        ScriptedGit.reftableClone()
                .answer(['fetch'], fetch)
                .answer(['ls-remote'], 0, "${'1' * 40}\trefs/heads/gnomish/PROJ\n")
                .answer(['rev-parse'], 1)
                .answer(['remote'], 0, 'https://example.invalid/repo.git\n')
    }

    /** Every fetch refused as an unreachable origin. */
    private static ScriptedGit fetchRefused() {
        originCarries(GitCommandResult.of(128, '', 'fatal: unable to access origin\n'))
    }

    /** No-wait budget: the same three attempts and doubling backoff, with the sleeps virtualized. */
    private TaskBranchLocator instantRetryLocator(GitRunner boundRunner = runner) {
        new TaskBranchLocator(boundRunner, new GitInfrastructureRetry(
                        VirtualTimeEquipment.waitingOn({ Duration ignored -> } as Sleeper), GitInfrastructureRetry.DEFAULT_ATTEMPTS, Duration.ofMillis(1)))
    }

    // Scenario: tracker/tracker-take / Every non-confirming locate failure is infrastructure
    def "FR6: an unreachable origin is unavailable, never absent — no fresh branch may be forked from it"() {
        given: 'a clone whose origin URL points nowhere, so neither the fetch nor the probe gets an answer'
        def clone = initWorkingRepo(tempDir, 'clone-unreachable')
        commit(clone, 'a.txt', 'first')
        addRemote(clone, 'origin', tempDir.resolve('does-not-exist.git').toString())

        when:
        def location = instantRetryLocator().locate(clone, 'PROJ-8')

        then: 'the lookup reports what it is — unestablished — and names why'
        location instanceof BranchLocation.Unavailable
        (location as BranchLocation.Unavailable).reason().contains('origin did not answer whether gnomish/PROJ-8')
    }

    // Scenario: tracker/tracker-take / Every non-confirming locate failure is infrastructure
    // Scenario: tracker/tracker-take / Fetch failure never forks a duplicate branch
    def "FR6: a branch origin confirms it carries but the fetch could not deliver is unavailable"() {
        given: 'a git that answers the branch probe but fails every fetch'
        def git = fetchRefused()

        when:
        def location = instantRetryLocator(git).locate(tempDir, 'PROJ-9')

        then:
        location instanceof BranchLocation.Unavailable
        def reason = (location as BranchLocation.Unavailable).reason()
        reason.contains('origin carries gnomish/PROJ-9')

        and: 'the reason names what the fetch itself did, so the abort diagnoses more than "failed"'
        reason.contains('the narrow fetch exited 128')
        reason.contains('unable to access origin')
    }

    // FR6 of harden-task-branch-contract: what the narrow fetch delivered is read off the tracking
    //     ref, never off its exit code — a fetch that reports success without creating the ref has
    //     delivered nothing, and answering RemoteTracking there hands the caller a ref that is not
    //     there, which every reader then resolves against.
    def "FR6: a fetch that exits zero without creating the ref is unavailable, never remote-tracking"() {
        given: 'a git whose fetch succeeds silently while origin confirms it carries the branch'
        def git = originCarries(GitCommandResult.of(0, '', ''))

        when:
        def location = instantRetryLocator(git).locate(tempDir, 'PROJ-13')

        then: 'the absent ref decides, not the zero exit'
        location instanceof BranchLocation.Unavailable
        (location as BranchLocation.Unavailable).reason().contains('origin carries gnomish/PROJ-13')
    }

    // Scenario: tracker/tracker-take / Fetch failure never forks a duplicate branch
    // Scenario: tracker/tracker-take / The locate retry spends its own budget
    def "FR6: the unsettled lookup is re-attempted under the infrastructure budget before giving up"() {
        given:
        def git = fetchRefused()

        when:
        instantRetryLocator(git).locate(tempDir, 'PROJ-10')

        then: 'the fetch ran once per attempt of the budget, and no more'
        git.calls(['fetch']).size() == GitInfrastructureRetry.DEFAULT_ATTEMPTS
    }

    // FR6 of harden-task-branch-contract: "this clone has no origin" is itself the answer of a git
    //     invocation, so a fetch cut off on its deadline must not be followed by a configuration
    //     read whose own silence is then spent as a second vote for absence.
    // Scenario: tracker/tracker-take / Every non-confirming locate failure is infrastructure
    def "FR6: a fetch cut off on its deadline is unavailable even when the origin read fails too"() {
        given: 'a git that stalls on every network command and denies having an origin at all'
        def cutOff = GitCommandResult.of(143, '', '', Termination.TIMED_OUT)
        def stalling = ScriptedGit.reftableClone()
                .answer(['fetch'], cutOff)
                .answer(['ls-remote'], cutOff)
                .answer([
                    'rev-parse',
                    '--git-common-dir'
                ], 0, '.git\n')
                .answer(['rev-parse'], 1)
                .answer(['remote'], 128)
        def oneAttempt = new GitInfrastructureRetry(VirtualTimeEquipment.waitingOn({ Duration ignored -> } as Sleeper), 1, Duration.ofMillis(1))

        when:
        def location = new TaskBranchLocator(stalling, oneAttempt).locate(tempDir, 'PROJ-12')

        then: 'the unestablished lookup says so, instead of the absence that forks a duplicate branch'
        location instanceof BranchLocation.Unavailable
        (location as BranchLocation.Unavailable).reason().contains('origin did not answer whether gnomish/PROJ-12')
    }
}
