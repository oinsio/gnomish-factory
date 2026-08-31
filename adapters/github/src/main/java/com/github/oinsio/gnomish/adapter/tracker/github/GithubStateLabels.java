package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.StateLabels;
import java.util.List;

/**
 * The four configured label names this adapter indexes task state with, and the one place a raw
 * list of label names on an issue becomes the port's {@link StateLabels} presence facts. Kept as a
 * value passed to every reader that reports facts, so the four names are resolved once in the
 * factory and no reader re-derives which label means what.
 *
 * <p>Presence, not interpretation: an issue wearing both ready and working — the claim sequence's
 * own kill window — is reported wearing both, and core decides what that means (FR19 of
 * harden-task-branch-contract).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR19 of harden-task-branch-contract.
 *
 * @param ready the configured ready-label name; never blank
 * @param working the configured working-label name; never blank
 * @param needsHuman the configured needs-human-label name; never blank
 * @param delivered the configured delivered-label name; never blank
 */
public record GithubStateLabels(String ready, String working, String needsHuman, String delivered) {

    /**
     * The presence facts of an open issue wearing {@code names}.
     *
     * @param names the label names the issue carries, verbatim from the API; never null
     * @return the port-level presence facts; never null
     */
    public StateLabels observed(List<String> names) {
        return new StateLabels(
                names.contains(ready),
                names.contains(working),
                names.contains(needsHuman),
                names.contains(delivered),
                false);
    }
}
