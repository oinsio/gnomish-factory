package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.TaskBranchAdoption
import com.github.oinsio.gnomish.adapter.git.TaskBranchLocator
import com.github.oinsio.gnomish.adapter.git.TaskBranchRefs
import com.github.oinsio.gnomish.adapter.git.VirtualTimeGitRetries
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Two adopters of one remote-tracking-only task branch in one clone at the same time (FR7, NFR-R1
 * of fix-terminal-receipt-convergence; design D9; {@code lock-scope.md}, "Writers from different
 * processes" and "Specs"). Each adopter is its own process in production, so each runs here over
 * its own {@link GitProcessRunner} — no in-JVM lock is shared — on its own thread, against a real
 * clone of a bare origin.
 *
 * <p>Both locate first, so both decide to adopt; they then meet at a barrier and reach the {@code
 * create} together. The create on absence is the first and only lock behind a decision: exactly one
 * lands — the head's reflog gains one entry — and the loser re-reads the branch as local and
 * answers already local. Neither fails, and the head names the tracking tip. Each feature races
 * {@link #ROUNDS} fresh worlds, so the outcome does not rest on one interleaving.
 */
class BranchAdoptionRaceSpec extends Specification implements BareGitRepoFixture {

    private static final int ROUNDS = 5

    private static final String TASK = 'RACE-1'

    @TempDir
    Path tempDir

    // Scenario: git/git-task-persistence / Two adopters land one head
    def "FR7, NFR-R1: two adopters of one clone land one create and both answer a local branch at one tip"() {
        expect: 'in every round, one adopted, the other already local, the head created once at the tracking tip'
        (1..ROUNDS).every { racedAndConverged(it) }
    }

    private boolean racedAndConverged(int round) {
        Path world = Files.createDirectories(tempDir.resolve("round-${round}"))
        Path clone = initWorkingRepo(world, 'clone')
        commit(clone, 'a.txt', 'seed')
        String tip = currentHead(clone)
        Path origin = initBareRepo(world, 'origin.git')
        addRemote(clone, 'origin', origin.toString())
        gitOutput(clone, 'push', 'origin', tip + ':' + TaskBranchRefs.head(TASK))
        assert fetchFromOrigin(clone, TaskBranchRefs.head(TASK) + ':' + TaskBranchRefs.tracking(TASK)).exitCode() == 0

        List<BranchAdoption> answers = race(clone)

        assert answers.count { it instanceof BranchAdoption.Adopted } == 1
        assert answers.contains(new BranchAdoption.Adopted(TASK))
        assert answers.contains(new BranchAdoption.AlreadyLocal(TASK))
        assert gitOutput(clone, 'rev-parse', TaskBranchRefs.head(TASK)) == tip
        assert gitOutput(clone, 'reflog', 'show', '--format=%H', TaskBranchRefs.head(TASK)).readLines().size() == 1
        true
    }

    /** Two adopters, each over its own runner: both locate, meet, then adopt at once. */
    private static List<BranchAdoption> race(Path clone) {
        def meet = new CyclicBarrier(2)
        def adopters = (1..2).collect {
            new FutureTask<BranchAdoption>({
                def runner = new GitProcessRunner()
                def locator = new TaskBranchLocator(runner, VirtualTimeGitRetries.gitInfrastructure())
                BranchLocation location = locator.locate(clone, TASK)
                assert location instanceof BranchLocation.RemoteTracking
                meet.await(30, TimeUnit.SECONDS)
                new TaskBranchAdoption(runner, locator).adopt(clone, TASK, location)
            } as Callable<BranchAdoption>)
        }
        adopters.each {
            Thread.ofPlatform().name('branch-adoption-race').start(it)
        }
        adopters.collect { it.get(60, TimeUnit.SECONDS) }
    }
}
