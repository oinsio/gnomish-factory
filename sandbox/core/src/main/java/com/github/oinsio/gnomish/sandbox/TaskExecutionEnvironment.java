package com.github.oinsio.gnomish.sandbox;

import com.github.oinsio.gnomish.domain.engine.Finding;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The single opaque port through which the factory owns a task's working-copy
 * lifecycle, runs every gnome-product process, and moves factory-authored files
 * to and from the environment (design D1). One host adapter and one container
 * adapter (later task group) implement it today; Colima-VM, k8s, and microVM
 * adapters follow as swaps behind the same contract.
 *
 * <p>The contract is host-agnostic: it speaks git transport and streams, and
 * SHALL NOT assume a filesystem shared between factory and environment. A
 * "volume path on disk" is a private detail of the local adapter, never part of
 * this interface. There is deliberately <em>no</em> snapshot operation — an
 * image of a gnome-touched environment must be impossible by construction (D1).
 *
 * <p>File operations ({@link #putFile}, {@link #readFile}) are valid only
 * between rounds, never mid-round, and their paths are factory-chosen strings
 * that resolve under one of two environment-owned roots: the working copy, or
 * the per-environment scratch area exposed by {@link #scratchRoot()}. Content
 * received from the environment is inert data — never executed, never
 * interpolated into commands, refspecs, or paths, never materialized as a
 * factory-owned file (NFR-S3).
 *
 * <p>Implements FR1, FR2, FR3, FR4, FR14 of add-sandbox-core.
 */
public interface TaskExecutionEnvironment {

    /**
     * Prepares the working copy on {@code branch}, pinned at {@code commitPin}
     * when given (default: the branch tip) — the single operation behind
     * fresh-box verification, sandboxed judge environments, and
     * {@code --discard-work}. The pin is always factory-chosen; a caller SHALL
     * never pass a name or SHA that originated inside an environment.
     *
     * <p>An adapter that preserves the pre-change host protocol MAY already hold
     * the working copy checked out on {@code branch} at {@code commitPin} (the
     * factory git runner did so before constructing the environment); in that
     * case {@code branch}/{@code commitPin} assert the state the working copy is
     * expected to already be in, and materialize adopts it rather than fetching
     * afresh. The container and other isolated adapters materialize from the
     * branch alone (they share no filesystem with the factory), so for them the
     * arguments drive the checkout. See {@code HostTaskExecutionEnvironment}.
     *
     * @param branch the task branch to prepare; never null
     * @param commitPin a factory-chosen commit to pin the working copy at, or
     *     {@code null} for the branch tip
     */
    void materialize(String branch, @Nullable String commitPin);

    /**
     * Runs {@code command} against the working copy and returns a live handle to
     * the started process (streamed output, exit control). This is the sole
     * process-launch seam: no factory code path may spawn a gnome-product
     * process over a working copy directly (FR4).
     *
     * @param command the process to run; never null
     * @return a handle to the started process; never null
     * @throws ProcessStartException if the process could not be started
     */
    ExecHandle exec(ExecCommand command);

    /**
     * Writes a factory-authored file at the factory-chosen {@code path}, which
     * SHALL resolve under the working copy or the scratch root. Runs as the
     * in-box task user, never root.
     *
     * @param path the factory-chosen path, under the working copy or scratch
     *     root; never null
     * @param content the exact bytes to write; never null
     */
    void putFile(String path, byte[] content);

    /**
     * Reads at most {@code sizeCap} bytes of the file at the factory-chosen
     * {@code path} (under the working copy or scratch root), returning empty
     * when the file is absent. The content is bounded and never materialized as
     * a factory-owned file (NFR-S3).
     *
     * @param path the factory-chosen path to read; never null
     * @param sizeCap the maximum number of bytes to return; must be positive
     * @return the bounded content, or empty when the file is absent
     */
    Optional<byte[]> readFile(String path, long sizeCap);

    /**
     * Makes the task branch fetchable by the factory (container adapter: a
     * fast-forward-only fetch from the environment; host adapter: a no-op, the
     * branch is already in the factory clone). Precedes any push (FR5).
     */
    void harvest();

    /** Tears the environment down; SHALL be idempotent and remove all scratch content (NFR-R2). */
    void dispose();

    /**
     * The per-environment scratch root — a factory-writable location outside the
     * working copy for protocol files (e.g. findings) that must never dirty the
     * working copy or be harvested. The factory composes paths under this root
     * to hand to {@link #putFile}/{@link #readFile}. Scratch content dies with
     * {@link #dispose()}.
     *
     * @return the scratch root as a path string valid within the environment;
     *     never null
     */
    String scratchRoot();

    /**
     * This adapter's capability passport, reconciled fail-closed against a
     * stage's declared needs before the stage runs (FR14).
     *
     * @return the passport; never null
     */
    CapabilityPassport passport();

    /**
     * The egress denials this environment recorded since the previous call —
     * the per-round delta, so a consumer asking at round close receives exactly
     * that round's denials and never an earlier round's again.
     *
     * <p>Host-agnostic by construction: an environment without an egress guard
     * has nothing to report and answers with an empty list, which is a truthful
     * answer rather than a missing capability. Read-back is best-effort — an
     * unreadable or missing denial source yields an empty list and SHALL never
     * fail the round, the attempt, or the report (NFR-R1); denial observability
     * must not take a healthy round down.
     *
     * <p>Implements FR1, NFR-R1 of fix-denial-report-attachment.
     *
     * @return the denials recorded since the previous call; never null, possibly
     *     empty
     */
    default List<Finding> denialFindings() {
        return List.of();
    }

    /**
     * The environment's current denial read position, for the factory to commit
     * alongside the attempt whose denials it delimits and to hand back on resume
     * ({@link #restoreDenialCursor}).
     *
     * <p>Empty when the environment has no denial source, or has not read one yet
     * — there is then no position a later lease could resume from, and reading
     * from the source's beginning is the correct start.
     *
     * <p>Implements FR5 of fix-denial-report-attachment.
     *
     * @return the current cursor; never null, possibly empty
     */
    default Optional<DenialCursor> denialCursor() {
        return Optional.empty();
    }

    /**
     * Offers a cursor committed by an earlier lease, so the first read of this one
     * reports the round's own denials instead of replaying every denial the source
     * still holds. Valid only before the first {@link #denialFindings()} call of
     * this environment.
     *
     * <p>An offer is not an instruction: the environment SHALL apply the position
     * only if {@link DenialCursor#source()} identifies its own live denial source,
     * and SHALL ignore it otherwise (a resume on another machine, or onto a
     * recreated source) — a foreign position could silently filter out real
     * denials. An environment without a denial source ignores the offer entirely.
     *
     * <p>Implements FR5 of fix-denial-report-attachment.
     *
     * @param cursor the cursor an earlier lease committed; never null
     */
    default void restoreDenialCursor(DenialCursor cursor) {
        // No denial source: nothing to position. Overridden by guarded environments.
    }
}
