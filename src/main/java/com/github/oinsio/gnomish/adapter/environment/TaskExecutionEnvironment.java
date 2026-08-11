package com.github.oinsio.gnomish.adapter.environment;

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
}
