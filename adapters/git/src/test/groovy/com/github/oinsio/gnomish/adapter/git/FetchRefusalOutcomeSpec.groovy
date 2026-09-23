package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.BranchLocationRefusedException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.BranchShape
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5, NFR-R1 of own-git-transfer-argv (design D4): a fetch git's object validation refused is a
 * task-level refusal at every origin site that grades a failed fetch — the branch and tag refresh,
 * the commit refresh, and the task-branch locate — and never the infrastructure arm. Each site
 * asks {@link FetchRefusal#parse} before its probe or carriage decision, so no probe of origin runs
 * and the report names the message id and the object git refused.
 *
 * <p>The refusal is produced by a stand-in git that answers everything for real except {@code
 * fetch}, which fails with the stderr git 2.55.0 prints under {@code fetch.fsckObjects=true}
 * ({@link FetchRefusalSpec#FETCH_REFUSAL}) — a local bare origin cannot be talked into serving a
 * malformed object on demand.
 */
class FetchRefusalOutcomeSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private Path work
    private Path origin
    private Path clone
    private Path fetchLog

    def setup() {
        (work, origin, clone) = initBaseRefTopology(tempDir, ['v1.0']) { Path w ->
            gitOutput(w, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'tag', '-a', 'v1.0', '-m', 'release')
        }
        fetchLog = tempDir.resolve('argv.log')
    }

    private GitProcessRunner refusingRunner() {
        new GitProcessRunner(fetchRefusedGit().toString())
    }

    private BaseRefresh refresh() {
        new BaseRefresh(refusingRunner(), VirtualTimeGitRetries.gitInfrastructure())
    }

    def "FR5: a #kind base whose fetch fails validation is refused, naming the object, with no probe"() {
        given: 'the clone is behind origin, so the refresh has to fetch'
        assert gitExitCode(clone, 'tag', '-d', 'v1.0') == 0
        advance('develop', 'stale.txt')

        when:
        def outcome = refresh().refresh(clone, base)

        then: 'a task-level refusal, never the infrastructure arm'
        outcome instanceof BaseRefreshOutcome.Refused
        !(outcome instanceof BaseRefreshOutcome.Unavailable)

        and: 'the report names the message id and the object git refused'
        def report = (outcome as BaseRefreshOutcome.Refused).report()
        report.contains('missingEmail')
        report.contains(FetchRefusalSpec.OBJECT)
        report.contains(base)

        and: 'the refusal was decided from the stderr alone: no ls-remote probe of HEAD ran after the fetch'
        recordedSubcommands(fetchLog).count('fetch') == 1
        !probedHead()

        where:
        kind | base
        'branch' | 'develop'
        'tag' | 'v1.0'
    }

    def "FR5: a commit base whose fetch fails validation is refused, naming the object, with no probe"() {
        given: 'a commit this clone lacks'
        def advanced = advance('develop', 'g.txt')
        assert gitExitCode(clone, 'cat-file', '-e', advanced + '^{commit}') != 0

        when:
        def outcome = refresh().refresh(clone, advanced)

        then:
        outcome instanceof BaseRefreshOutcome.Refused
        def report = (outcome as BaseRefreshOutcome.Refused).report()
        report.contains('missingEmail')
        report.contains(FetchRefusalSpec.OBJECT)
        report.contains(advanced)

        and:
        !probedHead()
    }

    def "FR5: a task-branch locate whose fetch fails validation is refused, never unavailable"() {
        given: 'origin carries the task branch and the clone holds no ref for it'
        def taskClone = cloneLackingTaskBranch('task', 'PROJ-1')

        when:
        def location = new TaskBranchLocator(refusingRunner(), VirtualTimeGitRetries.gitInfrastructure())
                .locate(taskClone, 'PROJ-1')

        then: 'the refusal arm, decided before origin is asked to confirm the branch'
        location instanceof BranchLocation.Refused
        def report = (location as BranchLocation.Refused).report()
        report.contains('missingEmail')
        report.contains(FetchRefusalSpec.OBJECT)
        report.contains('gnomish/PROJ-1')
        recordedSubcommands(fetchLog).count('ls-remote') == 0

        and: 'the refusal is deterministic, so the infrastructure budget spends no second fetch on it'
        recordedSubcommands(fetchLog).count('fetch') == 1
    }

    def "FR5: the take's shape classification turns a refused locate into a quarantine shape"() {
        given: 'origin carries the task branch and the clone holds no ref for it'
        def taskClone = cloneLackingTaskBranch('shape', 'PROJ-2')

        when:
        def shape = new GitTaskBranches(refusingRunner(), ClaimEpochSource.NONE).classifyShape(taskClone, 'PROJ-2')

        then: 'Corrupt: the branch parks for a human on this first classification, spending no attempt'
        shape instanceof BranchShape.Corrupt
        (shape as BranchShape.Corrupt).reason().contains('missingEmail')
        (shape as BranchShape.Corrupt).reason().contains(FetchRefusalSpec.OBJECT)
    }

    def "FR5: #reader refuses a refused locate with the report, never as unavailable or absent"() {
        given:
        def taskClone = cloneLackingTaskBranch(reader, 'PROJ-3')

        when:
        read.call(refusingRunner(), taskClone)

        then:
        def ex = thrown(BranchLocationRefusedException)
        ex.message.contains('PROJ-3')
        ex.message.contains('missingEmail')
        ex.message.contains(FetchRefusalSpec.OBJECT)

        where:
        reader | read
        'readState' | { GitProcessRunner r, Path c ->
            new BranchStateReader(r).read(c, 'PROJ-3')
        }
        'readDelivered' | { GitProcessRunner r, Path c ->
            new DeliveredBranchReader(r).read(c, 'PROJ-3')
        }
        'ensureLocalBranch' | { GitProcessRunner r, Path c ->
            new ContainerResumeBranch(r, ClaimEpochSource.NONE).ensureLocalBranch(c, 'PROJ-3')
        }
        'usage' | { GitProcessRunner r, Path c ->
            new UsageHistoryWalker(r).walk(c, 'PROJ-3')
        }
    }

    /** Adds a commit on {@code branch} in the upstream work repo and publishes it to origin. */
    private String advance(String branch, String file) {
        gitOutput(work, 'checkout', branch)
        commit(work, file, 'more')
        assert gitExitCode(work, 'push', 'origin', branch) == 0
        gitOutput(work, 'rev-parse', 'HEAD')
    }

    /**
     * A single-branch clone of a bare origin that carries {@code gnomish/<taskId>}: the clone holds
     * neither a local nor a remote-tracking ref for the task branch, so the locate has to fetch.
     */
    private Path cloneLackingTaskBranch(String name, String taskId) {
        Path bare = initBareRepo(tempDir, "origin-${name}.git")
        Path seed = initWorkingRepo(tempDir, "origin-${name}-seed")
        commit(seed, 'seed.txt', 'seed')
        addRemote(seed, 'origin', bare.toString())
        assert gitExitCode(seed, 'push', 'origin', 'HEAD:refs/heads/main') == 0
        assert gitExitCode(seed, 'checkout', '-b', "gnomish/${taskId}") == 0
        commit(seed, 'f.txt', 'wanted')
        assert gitExitCode(seed, 'push', 'origin', "gnomish/${taskId}") == 0
        seedClone(tempDir, bare.toString(), tempDir.resolve("clone-${name}"), '--branch', 'main', '--single-branch')
    }

    /** Whether the origin probe's own {@code ls-remote origin HEAD} ran through the stand-in. */
    private boolean probedHead() {
        Files.exists(fetchLog) && Files.readAllLines(fetchLog).any {
            it.contains('ls-remote') && it.endsWith('HEAD')
        }
    }

    /**
     * A git that logs every argv, fails every fetch with git 2.55.0's validation refusal, and
     * passes everything else — refs reads included — to the real binary.
     */
    private Path fetchRefusedGit() {
        Path stderr = tempDir.resolve('fsck-refusal.stderr')
        stderr.toFile().text = FetchRefusalSpec.FETCH_REFUSAL
        Path script = tempDir.resolve('fetch-refused-git.sh')
        script.toFile().text = """#!/bin/sh
echo "\$@" >> "${fetchLog}"
for a in "\$@"; do
  case "\$a" in
    fetch) cat '${stderr}' 1>&2; exit 128;;
  esac
done
exec git "\$@"
"""
        script.toFile().executable = true
        script
    }
}
