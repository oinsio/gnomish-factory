package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testfixtures.standin.StandInLog
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reusable Spock fixture: a stand-in {@code git} binary whose {@code push} never returns, so a
 * spec can drive the two outcomes no real remote can be asked to produce on demand — a push killed
 * on its deadline, and a push cut short by a shutdown.
 *
 * <p>Every read the factory's push points make around a push is answered; the remote's answer
 * ({@link #lsRemoteOut}, {@link #lsRemoteExit}) and the branch {@code HEAD} is on
 * ({@link #headBranch}) are files beside the stand-in that the spec rewrites to script what
 * {@code origin} says. What happens <em>during</em> the push is the preset's: {@code stall-push}
 * changes nothing, {@code stall-push-lands} makes the remote carry the tip before the stall,
 * {@code stall-push-origin-gone} makes it stop answering. Each push is recorded before it stalls,
 * so a spec counts the attempts ({@link #pushAttempts}) and interrupts one only once it is in
 * flight ({@link #awaitPushStarted}).
 *
 * <p>The stand-in is a committed preset reached through one per-run link per directory (ADR
 * 0015). Supports FR7, FR8 of bound-subprocess-commands; implements FR2 of kill-expensive-mutants
 * (design D4) and FR24 of supervise-daemon-loops-and-embed-dashboard.
 */
trait StallingGitFixture {

    /** The commit the stand-in reports as every local tip; a spec compares remote answers to it. */
    static final String STALLED_TIP = '1111111111111111111111111111111111111111'

    /** The remote's answer to {@code ls-remote}; empty means "origin has no such branch". */
    Path lsRemoteOut(Path dir) {
        seed(dir, 'ls-remote-out', '')
    }

    /** The exit code {@code ls-remote} answers with; non-zero means "origin never answered". */
    Path lsRemoteExit(Path dir) {
        seed(dir, 'ls-remote-exit', '0')
    }

    /** The branch the stand-in reports {@code HEAD} is on, for the round-boundary preconditions. */
    Path headBranch(Path dir) {
        seed(dir, 'head-branch', 'gnomish/detached')
    }

    /** How many pushes the stand-in was asked to make. */
    int pushAttempts(Path dir) {
        StandInLog.blocks(pushLink(dir)).size()
    }

    /** Blocks until a push is in flight, so an interrupt lands on the push and not before it. */
    void awaitPushStarted(Path dir) {
        StallingGit.awaitStall(pushLink(dir))
    }

    /**
     * The stand-in binary for {@code dir}, one per directory: {@code stall-push} unless the spec
     * names a variant. It answers {@code remote get-url}, {@code rev-parse} (both the branch tip and
     * the clone key the mutation lock resolves), {@code symbolic-ref}, {@code ls-remote} and
     * {@code merge-base --is-ancestor}, and stalls on {@code push} for ten minutes, so a push only
     * ends on its deadline or an interrupt.
     */
    Path stallingGit(Path dir, String preset = 'stall-push') {
        Path link = pushLink(dir)
        if (Files.isSymbolicLink(link)) {
            assert Files.readSymbolicLink(link) == StandIn.preset(preset):
            "${dir} already holds a stand-in of another preset"
            return link
        }
        lsRemoteOut(dir)
        lsRemoteExit(dir)
        headBranch(dir)
        StallingGit.marked(link, preset)
    }

    private static Path pushLink(Path dir) {
        dir.resolve('stalling-git')
    }

    private static Path seed(Path dir, String name, String initial) {
        Path file = StandIn.beside(pushLink(dir), name)
        if (!Files.exists(file)) {
            file.toFile().text = initial
        }
        file
    }
}
