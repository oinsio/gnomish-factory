package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.Set;

/**
 * This adapter's half of the pin-path contract from add-sandbox-core FR16/D10: the pin set a
 * future pin-check guard will check byte-wise against the base branch before ever invoking {@link
 * GithubWorkflowRunPoll} is the union of the stage law's user-declared paths and the paths this
 * adapter contributes. This adapter contributes exactly the {@code checkId} workflow file — no
 * directory-wide pin (design D3): a repo holds many workflows while one check uses one, so pinning
 * {@code .github/workflows/**} wholesale would permanently fail every task whose job is editing
 * unrelated CI.
 *
 * <p><b>Provisional, adapter-side only.</b> add-sandbox-core's pin-check guard component (FR16,
 * design D10, task 8.3 of add-sandbox-core) does not exist yet in this codebase — grep for a "pin"
 * guard in {@code src/main/java} finds nothing beyond this class. No concrete Java interface for a
 * "pin path contributor" is named anywhere in add-sandbox-core's specs or tasks (only prose: "pin
 * set = law-declared paths &cup; adapter-contributed paths ... byte-compare vs base branch, diff =
 * Fail, adapter never invoked"), so this class is deliberately a small static method rather than an
 * implementation of a port that does not exist. When the guard lands it is expected to call this
 * method (or something shaped like it) to obtain this adapter's contribution and union it with the
 * law-declared paths itself; this class may be adjusted then to match the guard's actual call
 * shape.
 *
 * <p>The "early substitution is caught at the point of use" scenario (the spec's own scenario
 * name) is a property of the guard, not of this class: the guard runs its byte-compare
 * immediately before each poll, at every stage that declares the check — so a workflow file
 * substituted during an earlier stage is caught here, at this check's point of use, not merely
 * once at pipeline start. This adapter's only obligation toward that property is to name its pin
 * paths correctly (this method) and to never be invoked except through {@link
 * GithubWorkflowRunPoll#poll}, which the guard is expected to gate. That gating does not exist
 * yet — this class cannot be exercised end-to-end until add-sandbox-core's guard lands.
 *
 * <p>Implements FR4 of add-external-check-github-actions. Depends on add-sandbox-core FR16/D10 for
 * the guard that consumes this.
 */
final class GithubCheckPinPaths {

    private GithubCheckPinPaths() {}

    /**
     * Returns the pin paths this adapter contributes for {@code check}: exactly the {@code
     * checkId} workflow file, narrow per design D3. The pin-check guard is expected to union this
     * with the stage law's user-declared pin paths (add-sandbox-core FR16).
     *
     * @param check the external check whose {@code checkId} is the workflow file path (FR1)
     * @return a single-element set containing {@code check.checkId()}; never null or empty
     */
    static Set<String> contributedBy(VerifyCheck.External check) {
        return Set.of(check.checkId());
    }
}
