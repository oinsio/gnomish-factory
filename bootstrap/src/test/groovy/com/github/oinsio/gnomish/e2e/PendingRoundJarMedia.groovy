package com.github.oinsio.gnomish.e2e

import static com.github.oinsio.gnomish.app.HostVerificationKill.registerRef

import com.github.oinsio.gnomish.app.HostVerificationKill
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path

/**
 * The durable media of {@link PendingRoundKillJarE2eSpec}'s journey (task 7.1 of
 * extract-agent-round-runner, M5): the fixture service — the per-run link to its stand-in preset, whose
 * request log is the service's own record of what it was asked — the bare origin a task's packaged
 * instances share, and each instance's project log. Building the project ({@link #published}, {@link
 * #otherClone}) and reading what the processes left behind live here; the processes and their kills
 * live in the spec.
 */
final class PendingRoundJarMedia {

    private static final String HOST = 'factory:\n  bindings:\n    default: host\n'

    final String task
    final Path service
    final Path origin
    final Path clone

    private PendingRoundJarMedia(String task, Path service, Path origin, Path clone) {
        this.task = task
        this.service = service
        this.origin = origin
        this.clone = clone
    }

    /**
     * The {@code e2e-pending-round} tree with the service's link — at preset {@code holding} — as the
     * stage's program, published to a bare origin beside it and bound to the host.
     */
    static PendingRoundJarMedia published(String task, String holding) {
        Path clone = E2eGitTree.copyOf('e2e-pending-round')
        Path service = StandIn.recording(Files.createTempDirectory('gnomish-e2e-service'), holding)
        clone.resolve('.gnomish/config.yaml').toFile() << "    program: [${service}]\n"
        Path origin = clone.resolveSibling('origin.git')
        E2eGitTree.publish(clone, origin)
        E2eProcessHarness.projectConfig(clone, HOST)
        new PendingRoundJarMedia(task, service, origin, clone)
    }

    /** Another instance's host: a fresh clone of origin, its project bound to the host. */
    Path otherClone() {
        Path other = Files.createTempDirectory('gnomish-e2e-other').resolve('e2e-pending-round')
        E2eGitTree.git(other.parent, 'clone', '--quiet', origin.toString(), other.toString())
        E2eProcessHarness.projectConfig(other, HOST)
        other
    }

    /** What the service was asked, in order, as {@code <operation> <polled key or ->}; its describes left out. */
    List<String> requests() {
        Path log = StandIn.log(service)
        if (!Files.exists(log)) {
            return []
        }
        Files.readString(log).split(/(?m)^--$/).collect { String block ->
            Map<String, String> fields = block.readLines().findAll {
                it.contains('=')
            }.collectEntries {
                int at = it.indexOf('=')
                [(it.substring(0, at)): it.substring(at + 1)]
            }
            String key = fields['GNOMISH_PENDING_KEY']
            [
                fields['arg.1'],
                key == null || key == 'unset' ? '-' : key.replaceAll(/^\[|\]$/, '')
            ]
        }.findAll {
            it[0] != null && it[0] != 'describe'
        }.collect {
            it.join(' ')
        }
    }

    /** The key fields of every round line in {@code clone}'s project log: one per round that ended on that instance. */
    static List<String> roundLines(Path clone) {
        Path log = ProjectRegistry.scan(E2eProcessHarness.HOME).resolve(clone.toAbsolutePath().normalize()).layout().logFile('default')
        Files.readAllLines(log).findAll {
            it.contains('execution finished: executor=service')
        }.collect { String line ->
            def keys = line =~ /pendingKey=\S*, resumedKey=\S*,/
            assert keys.find(): line
            keys.group()
        }
    }

    /** Asserts the round closed on one round commit above {@code below}: passing, the service's change in its tree, no pending entry. */
    void assertClosedAbove(String below) {
        String round = git('log', '--format=%H', '--grep', '^gnomish: round work#0$', branch())
        assert round && !round.contains('\n'): "one round commit expected on ${branch()}: ${round}"
        assert git('merge-base', '--is-ancestor', below, round) == ''
        assert git('ls-tree', '--name-only', round, 'new-1.txt') == 'new-1.txt'
        TaskState state = stateAt(round)
        assert state.attempts()*.result() == [AttemptRecord.Result.PASSED]
        assert state.pendingPolls().isEmpty()
    }

    /** What a further recovery may not change: origin's refs and what the service was asked. */
    Map<String, Object> footprint() {
        [origin: git('for-each-ref', '--format=%(refname) %(objectname)'), asked: requests()]
    }

    /** The subjects of the task branch at origin, newest first. */
    List<String> subjects(String range = branch()) {
        git('log', '--format=%s', range).readLines()
    }

    String tip() {
        git('rev-parse', branch())
    }

    String registered() {
        git('rev-parse', registerRef(task))
    }

    TaskState stateAt(String commit) {
        HostVerificationKill.stateAt(origin, commit)
    }

    String branch() {
        "refs/heads/gnomish/${task}"
    }

    private String git(String... args) {
        E2eGitTree.git(origin, args)
    }
}
