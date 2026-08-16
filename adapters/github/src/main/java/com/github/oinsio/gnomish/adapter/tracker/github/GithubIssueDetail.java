package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.DoNotMutate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The fields of a single "Get an issue" response body that {@link
 * GithubTaskFetcher} needs to build a {@code TrackerTask} (FR2, FR5 of
 * add-tracker-port): {@code title}/{@code body} for the snapshot, {@code
 * state}/{@code stateReason} to detect closure (the github-tracker spec's
 * "Human return is visible" requirement notes {@code state_reason} belongs in
 * revocation context), and {@code labelNames} to derive the logical state.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR2, FR5 of add-tracker-port.
 *
 * @param title the issue title; never blank for a real issue
 * @param body the issue body, or {@code null} when GitHub reports no description
 * @param state {@code "open"} or {@code "closed"}
 * @param stateReason GitHub's closure reason ({@code completed}, {@code
 *     not_planned}, {@code reopened}), or {@code null} when the issue is open
 *     or the field is absent
 * @param labelNames the names of every label currently on the issue
 */
record GithubIssueDetail(
        String title,
        @Nullable String body,
        String state,
        @Nullable String stateReason,
        List<String> labelNames) {

    // PIT M4 documented exception (build.gradle has the full rationale): @DoNotMutate because
    // this method crashes PIT's minion JVM (RUN_ERROR, not a real test gap) — the same
    // record-component-adjacent-private-method bytecode shape as ExecutorUsage's helpers
    // (hcoles/pitest#1285, JVMTI RedefineClasses restriction on JDK 17+). Exercised indirectly
    // through GithubTaskFetcherSpec's closed/open-issue fixtures (e.g. "state":"closed" mapping
    // to Gone).
    @DoNotMutate
    boolean isClosed() {
        return "closed".equals(state);
    }

    // PIT M4 documented exception (build.gradle has the full rationale): @DoNotMutate for the
    // same RUN_ERROR/RedefineClasses reason as isClosed() above. Exercised by
    // GithubTaskFetcherSpec's "maps a null body to an empty string in the snapshot" scenario.
    /** {@code body} mapped to {@code ""} when GitHub reports {@code null} (FR11's snapshot never carries null). */
    @DoNotMutate
    String bodyOrEmpty() {
        return body == null ? "" : body;
    }
}
