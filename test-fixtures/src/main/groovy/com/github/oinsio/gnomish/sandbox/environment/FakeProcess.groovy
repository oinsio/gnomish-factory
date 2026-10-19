package com.github.oinsio.gnomish.sandbox.environment

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
 * the code under test through its {@code ProcessLaunch}. {@code new FakeProcess()} has exited with
 * 0 and written nothing; {@code new FakeProcess(code, stdout)} is the canned base the scripted fakes
 * extend ({@code ScriptedProcess}, {@code FakeExecProcess}). {@link #running()} is a live process
 * whose exit happens when the spec calls {@link #exit(int)}, which releases every wait and closes the
 * pipes' write ends unless {@link #holdOpen()} came first — a descendant still holding them — and
 * then they stay open until {@link #release()}, or forever. {@link #awaitStdoutRead} and
 * {@link #onWaitReturn} let a spec place a step between the code's own instead of trusting a
 * script's scheduling. Both streams are {@link FakePipe}s, which behave as a Linux pipe (design D6
 * of make-pipe-reads-non-parking); no subclass overrides them, as {@code FakePipeSpec} pins.
 *
 * <p>{@link #descendants()} and {@link #toHandle()} are stubbed, not reaching an OS handle (FR11 of
 * bound-subprocess-commands); the pid is 0, never signalled, unless {@link #leading} names a group.
 */
class FakeProcess extends Process {

    private final CountDownLatch exitGate = new CountDownLatch(1)
    private final AtomicBoolean exitTaken = new AtomicBoolean()
    private final CompletableFuture<Process> exited = new CompletableFuture<>()
    private final AtomicReference<Runnable> waitReturn = new AtomicReference<>()
    private final FakePipe stdout = new FakePipe()
    private final FakePipe stderr = new FakePipe()
    private volatile int exitCode
    private volatile boolean heldOpen
    private volatile long pid

    /** The canned base: exited with 0, nothing written. */
    FakeProcess() {
        this(0, '')
    }

    /** The canned base with output: {@code stdout} waits in the pipe, the process has exited with {@code code}. */
    protected FakeProcess(int code, String stdout) {
        writeStdout(stdout)
        exit(code)
    }

    /**
     * The live form; the flag only selects this constructor over the canned ones. Protected so a
     * scripted subclass writes its pipes and arranges its exit before it is handed to the launch.
     */
    protected FakeProcess(boolean gated) {
    }

    /** A live process with open pipes, which exits only when the spec says so. */
    static FakeProcess running() {
        new FakeProcess(true)
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
        stdout.write(text.getBytes(StandardCharsets.UTF_8))
    }

    /** Writes {@code text} into the stderr pipe. */
    void writeStderr(String text) {
        stderr.write(text.getBytes(StandardCharsets.UTF_8))
    }

    /** Keeps both pipes open past the exit, as a descendant holding them would; called before {@link #exit}. */
    void holdOpen() {
        heldOpen = true
    }

    /** Closes both pipes' write ends, as the holder's death would: a read of either reaches end-of-file. Idempotent. */
    void release() {
        stdout.release()
        stderr.release()
    }

    /** Closes the stderr pipe's write end only, so a held stdout is the one stream left open. */
    void releaseStderr() {
        stderr.release()
    }

    /** Waits up to {@code bound} for a read of stdout to have returned bytes; whether one did. */
    boolean awaitStdoutRead(Duration bound) {
        stdout.awaitRead(bound)
    }

    /** Runs {@code action} once, on the thread whose {@code waitFor} next returns with the exit. */
    void onWaitReturn(Runnable action) {
        waitReturn.set(action)
    }

    /** The stdout pipe itself, for a spec that counts a reader's queries and reads or hooks a query. */
    FakePipe stdoutPipe() {
        stdout
    }

    /** The stderr pipe itself, for a spec that hooks a query of stderr (a {@link WritingHolder} on it). */
    FakePipe stderrPipe() {
        stderr
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
        stdout.source()
    }

    @Override
    InputStream getErrorStream() {
        stderr.source()
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
}
