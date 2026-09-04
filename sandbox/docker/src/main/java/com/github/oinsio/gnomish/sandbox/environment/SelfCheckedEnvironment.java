package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>A rejected box is <em>kept</em>, not disposed (FR3 of polish-sandbox-forensics): a failed
 * self-check is precisely the failure whose evidence — the image, the runtime, the allowlist as
 * they actually are inside the box — lives in the box, so {@link #materialize} stops it and
 * leaves its container, volume and network in place for the operator to inspect. The keep notice
 * logged here is the operator-facing carrier of the container name; the rejection exception is
 * rethrown untouched, and the keep is best-effort in both directions — a stop the runtime refuses
 * is warned about, never allowed to mask or reclassify the self-check failure (NFR-R1). Retention
 * of the kept box is governed entirely by the existing {@code sandbox-lifecycle} sweep, whose
 * universe it is already in: its ownership labels were stamped at creation (NFR-R2, NFR-C1).
 *
 * <p>This is the one place the keep is produced, for every role at once: the decorator wraps
 * round, judge and verification environments by construction, so no mode twin of the rule exists.
 *
 * <p>Implements FR8, FR9 of add-sandbox-core; FR1, NFR-R1 of
 * fix-denial-report-attachment; FR3, NFR-R1, NFR-O1, UX3 of polish-sandbox-forensics.
 */
public final class SelfCheckedEnvironment implements TaskExecutionEnvironment {

    private static final Logger log = LoggerFactory.getLogger(SelfCheckedEnvironment.class);

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
        try {
            selfCheck.verify();
        } catch (SelfCheckFailedException e) {
            keepRejectedBox();
            throw e;
        }
    }

    /**
     * Stops the rejected box and leaves its objects in place, so the operator can look inside the
     * very box that failed its own cage check (FR3, UX3). Best-effort: the keeper swallows a
     * runtime outage and reports it as a refused stop, which is worth a WARN here — the evidence
     * this path exists to preserve may be running away — but never worth changing what the caller
     * is told about the self-check itself (NFR-R1).
     */
    private void keepRejectedBox() {
        String key = guard.key();
        if (!new ContainerEnvironmentKeeper(guard.docker()).stopKeeping(key)) {
            log.warn(
                    OperatorEvent.SELF_CHECK_BOX_KEEP_FAILED.head()
                            + "could not stop rejected box {} for inspection; it is kept as the runtime left it",
                    FactoryDockerLabels.containerName(key));
        }
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
