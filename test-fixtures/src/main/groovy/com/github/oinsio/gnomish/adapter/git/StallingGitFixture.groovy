package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reusable Spock fixture: a stand-in {@code git} binary whose {@code push} never returns, so a
 * spec can drive the two outcomes no real remote can be asked to produce on demand — a push killed
 * on its deadline, and a push cut short by a shutdown.
 *
 * <p>Every read the factory's push points make around a push is answered from files under the
 * caller's temp directory, which the spec rewrites to script what {@code origin} says <em>after</em>
 * the push was killed: {@code ls-remote-out} / {@code ls-remote-exit} are the remote's answer,
 * {@code push-hook.sh} runs inside the push before it stalls (the seam for "the transfer landed
 * anyway"), {@code push-attempts} gains a line per push so a spec can assert no re-attempt was
 * spent, and {@code push-started} appears once a push is in flight so a spec can interrupt it
 * without racing.
 *
 * <p>Supports FR7, FR8 of bound-subprocess-commands.
 *
 * <p>The stall mechanics — stripping the leading {@code -c} pairs, the stall set, its length and
 * the answer table — are owned by {@link StallingGit}; this trait states only its scenario files.
 * Implements FR2 of kill-expensive-mutants (design D4).
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

    /** One line per push the stand-in was asked to make. */
    Path pushAttempts(Path dir) {
        seed(dir, 'push-attempts', '')
    }

    /** The branch the stand-in reports {@code HEAD} is on, for the round-boundary preconditions. */
    Path headBranch(Path dir) {
        seed(dir, 'head-branch', 'gnomish/detached')
    }

    /** The shell run inside a push, just before it stalls; a spec writes the scenario into it. */
    Path pushHook(Path dir) {
        seed(dir, 'push-hook.sh', '')
    }

    /** Appears once a push is in flight. */
    Path pushStarted(Path dir) {
        dir.resolve('push-started')
    }

    /** Blocks until a push is in flight, so an interrupt lands on the push and not before it. */
    void awaitPushStarted(Path dir) {
        long deadline = System.nanoTime() + 20_000_000_000L
        while (!Files.exists(pushStarted(dir)) && System.nanoTime() <deadline) {
            Thread.sleep(20)
        }
        assert Files.exists(pushStarted(dir)): 'the stand-in git never reached its push'
    }

    /**
     * Writes the stand-in binary into {@code dir} and returns its path. It answers {@code remote
     * get-url}, {@code rev-parse} (both the branch tip and the clone key the mutation lock
     * resolves), {@code symbolic-ref} (the branch {@code HEAD} is on), {@code ls-remote}, and
     * {@code merge-base --is-ancestor}, and stalls on {@code push}; the file-backed answers are
     * read when invoked, so a spec may rewrite the scenario files after this call. The stall
     * lasts {@link StallingGit#DEFAULT_STALL}, so a push only ends on its deadline or an interrupt.
     */
    Path stallingGit(Path dir) {
        new StallingGit()
                .stallOn('push')
                .beforeStall("echo attempt >> ${quoted(pushAttempts(dir))}")
                .beforeStall("sh ${quoted(pushHook(dir))}")
                .markOnStall(pushStarted(dir))
                .answer([
                    'rev-parse',
                    '--git-common-dir'
                ], '.git', 0)
                .answer('rev-parse', STALLED_TIP, 0)
                .answer('remote', 'https://example.invalid/repo.git', 0)
                .answerWith('ls-remote', "cat ${quoted(lsRemoteOut(dir))}; exit \"\$(cat ${quoted(lsRemoteExit(dir))})\"")
                .answerWith('symbolic-ref', "cat ${quoted(headBranch(dir))}")
                .answer('merge-base', '', 0)
                .write(dir)
    }

    private String quoted(Path path) {
        StallingGit.quote(path.toString())
    }

    private Path seed(Path dir, String name, String initial) {
        Path file = dir.resolve(name)
        if (!Files.exists(file)) {
            file.toFile().text = initial
        }
        return file
    }
}
