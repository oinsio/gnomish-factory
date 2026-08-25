package com.github.oinsio.gnomish.sandbox.environment

import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

/**
 * Shared {@link Process} test-double boilerplate: an empty stderr, a no-op
 * {@code destroy()}, and the process-tree seam the shared subprocess supervisor
 * reaches for on its kill path — the two methods every canned-output process
 * fake in this package ({@code ScriptedProcess}, {@code FakeExecProcess}) must
 * stub identically because {@link Process} forces an override even when the fake
 * never uses them.
 *
 * <p>{@link FakeProcess#descendants()} and {@link FakeProcess#toHandle()} are stubbed rather than
 * inherited because {@link Process}'s own implementations delegate to a real
 * OS handle and throw {@link UnsupportedOperationException} for a hand-written
 * subclass. A canned process has no tree and has already exited, so the honest
 * stand-ins are an empty descendant stream and a handle that reports itself
 * dead — which is exactly what the supervisor's snapshot/kill/reap sequence
 * expects to find (FR11 of bound-subprocess-commands).
 */
abstract class FakeProcess extends Process {

    @Override
    InputStream getErrorStream() {
        new ByteArrayInputStream(new byte[0])
    }

    @Override
    void destroy() {
    }

    @Override
    Stream<ProcessHandle> descendants() {
        Stream.empty()
    }

    @Override
    ProcessHandle toHandle() {
        new ExitedProcessHandle()
    }
}

/**
 * A {@link ProcessHandle} for a canned process: no tree, already exited, and an
 * {@code onExit} that is complete the moment it is asked — so the supervisor's
 * bounded grace and reap resolve immediately instead of spending their budget on
 * a process that was never real.
 */
final class ExitedProcessHandle implements ProcessHandle {

    @Override
    long pid() {
        0L
    }

    @Override
    Optional<ProcessHandle> parent() {
        Optional.empty()
    }

    @Override
    Stream<ProcessHandle> children() {
        Stream.empty()
    }

    @Override
    Stream<ProcessHandle> descendants() {
        Stream.empty()
    }

    @Override
    Info info() {
        throw new UnsupportedOperationException('a canned process has no OS-level info')
    }

    @Override
    CompletableFuture<ProcessHandle> onExit() {
        CompletableFuture.completedFuture(this)
    }

    @Override
    boolean supportsNormalTermination() {
        true
    }

    @Override
    boolean destroy() {
        false
    }

    @Override
    boolean destroyForcibly() {
        false
    }

    @Override
    boolean isAlive() {
        false
    }

    @Override
    int compareTo(ProcessHandle other) {
        Long.compare(pid(), other.pid())
    }

    @Override
    boolean equals(Object other) {
        other instanceof ExitedProcessHandle
    }

    @Override
    int hashCode() {
        ExitedProcessHandle.simpleName.hashCode()
    }
}
