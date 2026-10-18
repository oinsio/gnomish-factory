package com.github.oinsio.gnomish.sandbox.environment

import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

/**
 * The {@link ProcessHandle} of a {@link FakeProcess}: no tree, and an exit that is the fake's own
 * gated exit — so the supervisor's bounded grace and reap resolve the moment the fake exits, and a
 * signal through the handle ends the fake as it would a cooperative child.
 */
final class FakeProcessHandle implements ProcessHandle {

    private final FakeProcess process

    FakeProcessHandle(FakeProcess process) {
        this.process = process
    }

    @Override
    long pid() {
        process.pid()
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
        throw new UnsupportedOperationException('a fake process has no OS-level info')
    }

    @Override
    CompletableFuture<ProcessHandle> onExit() {
        process.onExit().thenApply { this as ProcessHandle }
    }

    @Override
    boolean supportsNormalTermination() {
        true
    }

    @Override
    boolean destroy() {
        boolean alive = process.isAlive()
        process.destroy()
        alive
    }

    @Override
    boolean destroyForcibly() {
        boolean alive = process.isAlive()
        process.destroyForcibly()
        alive
    }

    @Override
    boolean isAlive() {
        process.isAlive()
    }

    @Override
    int compareTo(ProcessHandle other) {
        Long.compare(pid(), other.pid())
    }

    @Override
    boolean equals(Object other) {
        other instanceof FakeProcessHandle && ((FakeProcessHandle) other).process.is(process)
    }

    @Override
    int hashCode() {
        System.identityHashCode(process)
    }
}
