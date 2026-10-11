package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.subprocess.Termination
import java.util.concurrent.CountDownLatch

/**
 * A scripted git ({@link ScriptedGit}) whose {@code push} never finishes on its own, so a spec of a
 * push point can drive the two outcomes no real remote can be asked to produce on demand: a push
 * the runner killed on its deadline ({@link #timingOut}), and a push cut short by a shutdown
 * ({@link #blocking}, released only by an interrupt of the pushing thread).
 *
 * <p>Every read the push points make around a push is answered: the local tip and the clone key
 * ({@code rev-parse}), the origin URL, the branch {@code HEAD} is on ({@link #headBranch}), the
 * ancestry check, and origin's listing ({@link #lsRemoteOut}, {@link #lsRemoteExit}), which a spec
 * sets to script what {@code origin} says. What happens <em>during</em> the push is the effect the
 * spec passes ({@link #landing}, {@link #originGone}), applied before the push ends.
 *
 * <p>Replaces the stand-in fixture {@code StallingGitFixture} for the specs above the runner
 * (design D27 of supervise-daemon-loops-and-embed-dashboard).
 */
final class StalledPush {

    /** The commit every local tip reads as; a spec compares remote answers to it. */
    static final String TIP = '1' * 40

    /** Origin's answer to {@code ls-remote}; empty means "origin has no such branch". */
    volatile String lsRemoteOut = ''

    /** The exit code {@code ls-remote} answers with; non-zero means "origin never answered". */
    volatile int lsRemoteExit = 0

    /** The branch {@code HEAD} is on, for the round-boundary preconditions. */
    volatile String headBranch = 'gnomish/detached'

    /** The runner's port the push point is built over. */
    final ScriptedGit git = new ScriptedGit()

    private StalledPush(Closure<GitCommandResult> pushEnds) {
        git.answer(['push'], pushEnds)
        .answer(['rev-parse'], 0, TIP + '\n')
        .answer(['remote'], 0, 'https://example.invalid/repo.git\n')
        .answer(['ls-remote']) {
            GitCommandResult.of(lsRemoteExit, lsRemoteOut, '')
        }
        .answer(['symbolic-ref']) {
            GitCommandResult.of(0, headBranch + '\n', '')
        }
        .answer(['merge-base'], 0)
        .answer([], 0)
    }

    /** A push the runner killed on its deadline, after {@code during} ran. */
    static StalledPush timingOut(Closure during = {}) {
        StalledPush push
        push = new StalledPush({
            during.call(push)
            GitCommandResult.of(143, '', '', Termination.TIMED_OUT)
        })
        push
    }

    /** A push that blocks until the pushing thread is interrupted, which answers INTERRUPTED. */
    static StalledPush blocking() {
        CountDownLatch never = new CountDownLatch(1)
        new StalledPush({
            try {
                never.await()
                GitCommandResult.of(0, '', '')
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt()
                GitCommandResult.of(143, '', '', Termination.INTERRUPTED)
            }
        })
    }

    /** During the push, origin takes the tip on {@code branch}: the kill lands too late. */
    static Closure landing(String branch) {
        return { StalledPush push ->
            push.lsRemoteOut = "${TIP}\trefs/heads/${branch}\n".toString()
        }
    }

    /** During the push, origin stops answering {@code ls-remote}. */
    static Closure originGone() {
        return { StalledPush push -> push.lsRemoteExit = 128 }
    }

    /** How many pushes were asked for. */
    int pushAttempts() {
        git.calls(['push']).size()
    }

    /**
     * Returns once {@code pusher}'s push is in flight, so an interrupt lands on the push and not
     * before it; fails when the pusher ends first (see {@link ScriptedGit#awaitCall}).
     */
    void awaitPushStarted(Thread pusher) {
        git.awaitCall(['push'], pusher)
    }
}
