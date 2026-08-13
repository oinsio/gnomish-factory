package com.github.oinsio.gnomish.adapter.environment;

import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A {@link TaskExecutionEnvironment} view that forwards every call to the
 * environment currently held by a supplier (in practice {@link
 * EnvironmentLease#current}): collaborators constructed once per run —
 * sandboxed attempt persistence, salvage — always act on the environment of the
 * stage in flight, across segment boundaries, without re-wiring. Lifecycle
 * operations are deliberately unsupported: the lease owns materialize/dispose.
 *
 * <p>Implements FR12 of add-sandbox-core.
 */
public final class LeasedEnvironment implements TaskExecutionEnvironment {

    private final Supplier<TaskExecutionEnvironment> current;

    /** @param current supplies the environment of the stage in flight; never null */
    public LeasedEnvironment(Supplier<TaskExecutionEnvironment> current) {
        this.current = current;
    }

    @Override
    public void materialize(String branch, @Nullable String commitPin) {
        throw new UnsupportedOperationException("the environment lease owns materialization");
    }

    @Override
    public ExecHandle exec(ExecCommand command) {
        return current.get().exec(command);
    }

    @Override
    public void putFile(String path, byte[] content) {
        current.get().putFile(path, content);
    }

    @Override
    public Optional<byte[]> readFile(String path, long sizeCap) {
        return current.get().readFile(path, sizeCap);
    }

    @Override
    public void harvest() {
        current.get().harvest();
    }

    @Override
    public void dispose() {
        throw new UnsupportedOperationException("the environment lease owns disposal");
    }

    @Override
    public String scratchRoot() {
        return current.get().scratchRoot();
    }

    @Override
    public CapabilityPassport passport() {
        return current.get().passport();
    }
}
