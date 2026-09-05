package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.CapabilityPassport;
import com.github.oinsio.gnomish.sandbox.DenialCursor;
import com.github.oinsio.gnomish.sandbox.DenialRead;
import com.github.oinsio.gnomish.sandbox.DenialRestoration;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A {@link TaskExecutionEnvironment} view that forwards every call to the
 * environment currently held by a supplier (in practice {@link
 * EnvironmentLease#current()}): collaborators constructed once per run —
 * sandboxed attempt persistence, salvage — always act on the environment of the
 * stage in flight, across segment boundaries, without re-wiring. Lifecycle
 * operations are deliberately unsupported: the lease owns materialize/dispose.
 *
 * <p>Every method of the port is forwarded, the interface's default methods
 * included: for a leaf a constant default is a truthful "I have no denial
 * source", but for a delegating view it is a lie about the leased
 * environment's capability — the guard's denials, cursor and restore would be
 * answered here with empty constants and never reach the box (FR6 of
 * fix-denial-attribution-durability, which is how the predecessor's cursor
 * feature shipped inert). The {@code DelegatingDecoratorCompletenessSpec}
 * architecture rule keeps this class complete mechanically.
 *
 * <p>Implements FR12 of add-sandbox-core; FR6 of
 * fix-denial-attribution-durability.
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

    @Override
    public DenialRead readDenials() {
        return current.get().readDenials();
    }

    @Override
    public Optional<DenialCursor> denialCursor() {
        return current.get().denialCursor();
    }

    @Override
    public void restoreDenials(DenialRestoration restoration) {
        current.get().restoreDenials(restoration);
    }
}
