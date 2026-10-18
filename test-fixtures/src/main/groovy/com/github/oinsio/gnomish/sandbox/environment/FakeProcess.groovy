package com.github.oinsio.gnomish.sandbox.environment

import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream

/**
 * The one {@link Process} test double (design D16 of fix-terminal-receipt-convergence), handed to
 * the code under test through its {@code ProcessLaunch}. Two forms:
 *
 * <ul>
 *   <li>{@code new FakeProcess()} — the canned base the scripted fakes of this package extend
 *       ({@code ScriptedProcess}, {@code FakeExecProcess}): already exited with 0, empty output, a
 *       no-op kill, so a subclass overrides only the streams and the exit it scripts.
 *   <li>{@link #running()} — a live process whose exit is gated: it happens when the spec calls
 *       {@link #exit(int)}, and every wait — {@code waitFor}, {@code onExit}, the handle's — is
 *       released by that call. Its stdout and stderr are {@link Pipe} ends: {@link #writeStdout}
 *       and {@link #writeStderr} write into them, and the exit closes them unless {@link #holdOpen()}
 *       was called first — the shape of a descendant still holding the pipe — in which case they
 *       stay open until {@link #release()}, or forever. A spec orders "exit, then the last write"
 *       itself instead of trusting a script's scheduling. Two latches let it place a step between
 *       the code's own: {@link #awaitStdoutRead} returns once a read of stdout has returned bytes,
 *       and an {@link #onWaitReturn} action runs on the waiting thread once its wait saw the exit.
 * </ul>
 *
 * <p>{@link #descendants()} and {@link #toHandle()} are stubbed because {@link Process}'s own
 * implementations reach a real OS handle and throw for a hand-written subclass: the fake has no
 * tree, and its handle reports the gated exit (FR11 of bound-subprocess-commands). Its pid is 0,
 * which the group sweep never signals, unless {@link #leading} names a group (design D19).
 */
class FakeProcess extends Process {

    private final CountDownLatch exitGate = new CountDownLatch(1)
    private final AtomicBoolean exitTaken = new AtomicBoolean()
    private final CompletableFuture<Process> exited = new CompletableFuture<>()
    private final AtomicReference<Runnable> waitReturn = new AtomicReference<>()
    private final Pipe stdout
    private final Pipe stderr
    private final SignallingInputStream stdoutEnd
    private final InputStream stderrEnd
    private volatile int exitCode
    private volatile boolean heldOpen
    private volatile long pid

    /** The canned base: exited with 0, nothing written. */
    FakeProcess() {
        this(null, null)
        exit(0)
    }

    private FakeProcess(Pipe stdout, Pipe stderr) {
        this.stdout = stdout
        this.stderr = stderr
        this.stdoutEnd = new SignallingInputStream(
                stdout == null ? InputStream.nullInputStream() : Channels.newInputStream(stdout.source()))
        this.stderrEnd = stderr == null ? InputStream.nullInputStream() : Channels.newInputStream(stderr.source())
    }

    /** A live process with open pipes, which exits only when the spec says so. */
    static FakeProcess running() {
        new FakeProcess(Pipe.open(), Pipe.open())
    }

    /** Reports {@code group}, a real group the spec started and ends itself, as this fake's pid. */
    FakeProcess leading(long group) {
        pid = group
        this
    }

    @Override
    long pid() {
        pid
    }

    /** Writes {@code text} into the stdout pipe; the bytes wait in the pipe until the drain reads them. */
    void writeStdout(String text) {
        write(livePipe(stdout), text)
    }

    /** Writes {@code text} into the stderr pipe. */
    void writeStderr(String text) {
        write(livePipe(stderr), text)
    }

    /** Keeps both pipes open past the exit, as a descendant holding them would; called before {@link #exit}. */
    void holdOpen() {
        livePipe(stdout)
        heldOpen = true
    }

    /** Closes both pipes' write ends: the drains read to end-of-file. Idempotent. */
    void release() {
        [stdout, stderr].findAll { it != null }.each { it.sink().close() }
    }

    /** Closes the stderr pipe's write end only, so a held stdout is the one stream left open. */
    void releaseStderr() {
        livePipe(stderr).sink().close()
    }

    /** Closes the stdout pipe's write end only, so a held stderr is the one stream left open. */
    void releaseStdout() {
        livePipe(stdout).sink().close()
    }

    /** Waits up to {@code bound} for a read of stdout to have returned bytes; whether one did. */
    boolean awaitStdoutRead(Duration bound) {
        stdoutEnd.awaitRead(bound)
    }

    /** Runs {@code action} once, on the thread whose {@code waitFor} next returns with the exit. */
    void onWaitReturn(Runnable action) {
        waitReturn.set(action)
    }

    /** Opens the gate: the process exits with {@code code}. The first exit wins; later ones change nothing. */
    void exit(int code) {
        if (!exitTaken.compareAndSet(false, true)) {
            return
        }
        exitCode = code
        if (!heldOpen) {
            release()
        }
        exitGate.countDown()
        exited.complete(this)
    }

    @Override
    OutputStream getOutputStream() {
        OutputStream.nullOutputStream()
    }

    @Override
    InputStream getInputStream() {
        stdoutEnd
    }

    @Override
    InputStream getErrorStream() {
        stderrEnd
    }

    @Override
    int waitFor() throws InterruptedException {
        exitGate.await()
        waitReturned()
        exitCode
    }

    @Override
    boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        exitGate.await(timeout, unit) && waitReturned()
    }

    @Override
    int exitValue() {
        if (isAlive()) {
            throw new IllegalThreadStateException('the fake process has not exited')
        }
        exitCode
    }

    @Override
    boolean isAlive() {
        exitGate.count> 0
    }

    @Override
    CompletableFuture<Process> onExit() {
        exited
    }

    /** A cooperative child: the polite signal ends it with the shell's code for SIGTERM. */
    @Override
    void destroy() {
        exit(143)
    }

    @Override
    Process destroyForcibly() {
        exit(137)
        this
    }

    @Override
    Stream<ProcessHandle> descendants() {
        Stream.empty()
    }

    @Override
    ProcessHandle toHandle() {
        new FakeProcessHandle(this)
    }

    private boolean waitReturned() {
        waitReturn.getAndSet(null)?.run()
        true
    }

    private static Pipe livePipe(Pipe pipe) {
        if (pipe == null) {
            throw new IllegalStateException('a canned FakeProcess has no pipes; use FakeProcess.running()')
        }
        pipe
    }

    private static void write(Pipe pipe, String text) {
        ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8))
        while (bytes.hasRemaining()) {
            pipe.sink().write(bytes)
        }
    }
}
