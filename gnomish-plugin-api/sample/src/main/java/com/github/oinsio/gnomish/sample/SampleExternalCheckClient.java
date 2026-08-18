package com.github.oinsio.gnomish.sample;

import com.github.oinsio.gnomish.app.findings.FindingsSanitizer;
import com.github.oinsio.gnomish.app.port.check.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.List;

/**
 * The port half of the sample check adapter: one poll, reaching for both pieces of api surface a
 * real external check needs.
 *
 * <p>It narrows the opaque {@link Workspace} the engine hands it to {@link AttemptCommitWorkspace}
 * to learn which attempt (snapshot) commit the round under verification is about — a third-party
 * check that could not do this would be polling a platform without knowing what to poll it for
 * (FR1) — and it routes the text it puts in a {@link Finding} through {@link FindingsSanitizer},
 * the same strip-and-cap hygiene every first-party adapter applies before findings reach the
 * tracker (FR2, NFR-S1).
 *
 * <p>The verdict itself is a stand-in: this module exists to be compiled, not run (see the package
 * javadoc), so the "platform call" is elided and the poll reports the commit it read.
 *
 * <p>Implements FR4, G1 of close-plugin-api-compilability-gap.
 */
final class SampleExternalCheckClient implements ExternalCheckClient {

    /** Tail cap for the stand-in finding, as a real adapter caps a fetched log tail. */
    private static final int DETAILS_CAP_CHARS = 4000;

    @Override
    public PollStatus poll(VerifyCheck.External check, Workspace workspace) {
        if (!(workspace instanceof AttemptCommitWorkspace attemptWorkspace)) {
            return new PollStatus.CannotVerify(
                    "sample check requires a sandboxed-mode workspace",
                    "got: " + (workspace == null ? "null" : workspace.getClass().getName()));
        }
        String sha = attemptWorkspace.attemptCommitSha();
        return new PollStatus.Fail(List.of(new Finding(
                "sample check did not pass",
                check.checkId(),
                FindingsSanitizer.capTail(FindingsSanitizer.strip("verified commit " + sha), DETAILS_CAP_CHARS))));
    }
}
