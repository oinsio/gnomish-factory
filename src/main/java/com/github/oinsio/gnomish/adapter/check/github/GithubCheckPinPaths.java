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
 * <p>The guard is {@link com.github.oinsio.gnomish.adapter.check.PinCheckedExternalCheckClient}
 * (task 8.3 of add-sandbox-core); {@link GithubCheckClientFactory#pinContributor()} exposes this
 * contribution to it as an {@link com.github.oinsio.gnomish.adapter.check.ExternalCheckPinContributor}.
 * The "early substitution is caught at the point of use" scenario is the guard's property: it
 * byte-compares immediately before each poll, at every stage that declares the check. This
 * adapter's obligation is to name its pin paths correctly (this method) and to be invoked only
 * through the guard, which every assembly wires (task 8.4).
 *
 * <p>Implements FR4 of add-external-check-github-actions; FR16 of add-sandbox-core.
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
