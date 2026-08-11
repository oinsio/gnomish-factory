package com.github.oinsio.gnomish.domain.pipeline;

import java.util.List;

/**
 * The sandbox declarations a stage carries in its Mechanism section
 * ({@code .claude/rules/stage-description.md} §5, "workspace requirements"): the
 * repo-side, tighten-only knobs the factory reconciles against the bound
 * adapter's capability passport (design D8, task 3.2) and uses to shape the
 * environment lifecycle (segment computation, task 3.3).
 *
 * <p>Two knobs:
 *
 * <ul>
 *   <li>{@code needs} — named requirements the stage places on its environment
 *       (e.g. {@code docker-inside}), reconciled against the adapter passport;
 *       an unmet need refuses the stage fail-closed (FR14). Carried here as
 *       inert data in declaration order — nothing is reconciled in the domain
 *       model (that is the binding-resolution concern of task group 3);</li>
 *   <li>{@code requiresFresh} — when {@code true}, the stage forces a new
 *       environment even within a contiguous same-binding segment, resetting
 *       everything outside the branch (FR13); {@code false} (the default) reuses
 *       the segment environment.</li>
 * </ul>
 *
 * <p>These are declarations a repo may only <em>tighten</em>: the repo never
 * names a binding or requests host execution — adapter binding and any
 * weakening live only in factory installation config (FR14). A binding request
 * in a manifest is refused at the adapter's structural tier before this record
 * is ever built, so no "requested binding" field exists here by construction.
 *
 * <p>The record is inert, immutable data: the needs list is defensively copied
 * and unmodifiable, and no semantic rule is enforced here — reconciliation
 * against the adapter passport is task group 3's concern, never a throwing
 * constructor.
 *
 * <p>Implements FR12, FR13 of add-sandbox-core.
 *
 * @param needs the stage's named environment requirements, in declaration order
 *     (FR12); possibly empty; immutable
 * @param requiresFresh whether the stage forces a fresh environment even within
 *     its segment (FR13); {@code false} by default reuses the segment
 */
public record Sandbox(List<String> needs, boolean requiresFresh) {

    public Sandbox {
        needs = List.copyOf(needs);
    }

    /**
     * The empty declaration — no needs, no forced freshness — carried by every
     * stage that declares no {@code sandbox} block, so the Mechanism always has a
     * non-null {@link Sandbox} and callers never null-check (design: absent
     * declarations are the reuse-segment, same-box defaults of FR13).
     *
     * @return a shared empty declaration
     */
    public static Sandbox none() {
        return new Sandbox(List.of(), false);
    }
}
