package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.CapabilityPassport;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.util.LinkedHashMap;
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
 * <p>Implements FR8, FR9 of add-sandbox-core.
 *
 * @param delegate the raw container environment; never null
 * @param selfCheck the probes run after every successful materialize; never null
 * @param guard the environment's egress guard, source of the proxy env fragment; never null
 */
public record SelfCheckedEnvironment(
        TaskExecutionEnvironment delegate, EnvironmentSelfCheck selfCheck, EgressGuard guard)
        implements TaskExecutionEnvironment {

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

    /** The decorated environment's egress guard, for denial-findings read-back (NFR-O1). */
    @Override
    public EgressGuard guard() {
        return guard;
    }
}
