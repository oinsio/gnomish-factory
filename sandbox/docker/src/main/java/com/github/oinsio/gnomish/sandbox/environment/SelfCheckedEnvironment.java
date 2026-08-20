package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.sandbox.CapabilityPassport;
import com.github.oinsio.gnomish.sandbox.DenialCursor;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A {@link TaskExecutionEnvironment} decorator enforcing the mandatory
 * fail-closed self-check (FR8, design D5) by construction: {@link #materialize}
 * delegates and then runs the environment's self-check before returning, so no
 * caller — round lease, fresh judge box, fresh-box verification — can obtain a
 * materialized guarded environment that has not proven its cage from inside.
 * A failed probe propagates as {@link SelfCheckFailedException} (an
 * infrastructure failure) and no gnome-product process ever executes in the
 * rejected environment.
 *
 * <p>Every {@link #exec} additionally merges the guard's proxy variables into
 * the factory-set env fragment (task 7.1, FR9): the reference image bakes the
 * same values, but processes must see them even under a stripped-down image.
 * Explicit caller-set variables win over the proxy fragment.
 *
 * <p>The guard is also this environment's denial source: {@link #denialFindings()}
 * delegates to it, so a consumer holding the port type reaches the round's
 * denials through the contract and never by downcasting to this class. The guard
 * itself is deliberately NOT exposed as a public accessor (FR1 of
 * fix-denial-report-attachment) — the contract is the whole surface.
 *
 * <p>Implements FR8, FR9 of add-sandbox-core; FR1, NFR-R1 of
 * fix-denial-report-attachment.
 */
public final class SelfCheckedEnvironment implements TaskExecutionEnvironment {

    private final TaskExecutionEnvironment delegate;
    private final EnvironmentSelfCheck selfCheck;
    private final EgressGuard guard;

    /**
     * @param delegate the raw container environment; never null
     * @param selfCheck the probes run after every successful materialize; never null
     * @param guard the environment's egress guard — source of the proxy env fragment and of the
     *     denial findings; never null
     */
    public SelfCheckedEnvironment(
            TaskExecutionEnvironment delegate, EnvironmentSelfCheck selfCheck, EgressGuard guard) {
        this.delegate = delegate;
        this.selfCheck = selfCheck;
        this.guard = guard;
    }

    @Override
    public void materialize(String branch, @Nullable String commitPin) {
        delegate.materialize(branch, commitPin);
        selfCheck.verify();
    }

    @Override
    public ExecHandle exec(ExecCommand command) {
        Map<String, String> env = new LinkedHashMap<>(guard.proxyEnvironment());
        env.putAll(command.env());
        return delegate.exec(new ExecCommand(command.command(), env, command.stdin(), command.mergeStderr()));
    }

    @Override
    public void putFile(String path, byte[] content) {
        delegate.putFile(path, content);
    }

    @Override
    public Optional<byte[]> readFile(String path, long sizeCap) {
        return delegate.readFile(path, sizeCap);
    }

    @Override
    public void harvest() {
        delegate.harvest();
    }

    @Override
    public void dispose() {
        delegate.dispose();
    }

    @Override
    public String scratchRoot() {
        return delegate.scratchRoot();
    }

    @Override
    public CapabilityPassport passport() {
        return delegate.passport();
    }

    /**
     * The guard's denials since the previous call — the per-round delta the guard's
     * own cursor maintains, best-effort (an unreadable log reads as empty, never a
     * failure; NFR-R1 of fix-denial-report-attachment).
     */
    @Override
    public List<Finding> denialFindings() {
        return guard.denialFindings();
    }

    /**
     * The guard's read position, for the factory to commit with the attempt it
     * delimits (FR5 of fix-denial-report-attachment).
     */
    @Override
    public Optional<DenialCursor> denialCursor() {
        return guard.denialCursor();
    }

    /**
     * Offers a committed cursor to the guard, which applies it only if it names
     * the guard's live container (FR5 of fix-denial-report-attachment).
     */
    @Override
    public void restoreDenialCursor(DenialCursor cursor) {
        guard.restoreDenialCursor(cursor);
    }
}
