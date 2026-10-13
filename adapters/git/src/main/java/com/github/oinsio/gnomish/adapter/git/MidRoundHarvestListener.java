package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException;
import com.github.oinsio.gnomish.logtext.FailureReason;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sandboxed twin of {@link MidRoundPushListener} (FR5, git-task-persistence
 * "Best-effort push"): notices a gnome commit landing mid-round <em>inside the
 * environment</em> and mirrors it out — harvest into the task's quarantine, the
 * admission of what landed, then a best-effort push of what was admitted.
 * The factory cannot watch the in-box filesystem, so tip observation is a
 * <b>rate-limited poll</b> on the factory side: each agent progress event is an
 * opportunity, but an actual harvest runs at most once per {@code minInterval},
 * so a commit-spamming gnome can never cause a fetch storm (design D3). A
 * harvest of an unchanged tip is close to a no-op, which is what makes polling
 * affordable. Event-driven tip detection, if it is ever added, must watch
 * {@code .git/logs/HEAD} — refs may be silently packed into {@code packed-refs}
 * — and may only <em>wake</em> this rate-limited poll, never command a fetch
 * itself (D3); it is deliberately not implemented here.
 *
 * <p>Push preconditions differ from the host listener by design: the admission
 * ({@link HarvestAdmission}, design D18 of tighten-spec-contract) is the
 * precondition — the harvested range descends from the branch tip and passed the
 * one {@link GnomeRangeInspection}, the round's own decision request the one
 * permitted state-directory path — and it moves the branch alone, never the
 * register. A refused or unjudged range moves nothing and skips the push with one
 * WARN, leaving the authoritative verdict to the round's close
 * (git-task-persistence "Push safety rules"); only a verdict — admitted or refused — settles a
 * harvested tip, so an unjudged one is judged again at the next poll. Like every {@link
 * AgentProgressListener}, {@link #onProgress} never throws.
 *
 * <p>A poll that keeps failing would otherwise cost one WARN per progress event for the whole
 * round, so failures report to a {@link RepeatSuppressor} through {@link MidRoundPollLog}, which
 * logs only the edges (FR4 of harden-logging-observability). The host-mode twin of this listener,
 * {@link MidRoundPushListener}, polls once per progress event too, and suppresses its own tip
 * subject the same way.
 *
 * <p>An observation is only ever a fact when git said so (FR13): a quarantine that does not resolve
 * <b>skips the observation</b> — the tip is reported neither moved nor lost, and no push decision
 * rests on it.
 *
 * <p><b>Lifecycle: one instance per round</b>, constructed right before the
 * round's {@code execute()} and discarded after, mirroring {@link
 * MidRoundPushListener}. The baseline is the tip the round opened on.
 *
 * <p>Kept in sync with {@link MidRoundPushListener}: both must poll the tip once per progress
 * event through {@link VerifiedTip} (a failed resolution skips the observation, never reduces to
 * a blank), report the failure streak edge-only through {@link MidRoundPollLog}, live one
 * instance per round, and push only behind a precondition that proves the pushed range — the
 * admission here, the {@link BestEffortPush} ancestry pre-check there.
 *
 * <p>Implements FR5 of add-sandbox-core; coordinates with FR11 of
 * add-git-workflow; FR13, NFR-S2 of tighten-spec-contract.
 */
public final class MidRoundHarvestListener implements AgentProgressListener {

    private static final Logger log = LoggerFactory.getLogger(MidRoundHarvestListener.class);

    private final TaskExecutionEnvironment environment;
    private final HarvestAdmission admission;
    private final String taskId;
    private final AdmissionRule rule;
    private final InstantSource clock;
    private final Duration minInterval;
    private final MidRoundPollLog pollLog;

    private @Nullable Instant lastPollAt;
    private String lastObservedTip;

    /**
     * @param environment the task's bound environment; {@code harvest()} is the only call made
     * @param admission the admission of what a harvest lands in the quarantine
     * @param openTip the tip the round opened on — its token's commit — the poll's baseline
     * @param rule the round's mid-round admission rule ({@link AdmissionRule#midRound}): its own
     *     decision request the one state-directory path a gnome commit may change
     * @param clock the poll rate-limit time source
     * @param minInterval the minimum time between two actual harvests; the factory-side rate
     *     limit of design D3
     * @param context whose round this is — the task id and branch (e.g. {@link
     *     TaskIdSanitizer#branchName}) and the suppressor its failure streaks live in
     */
    MidRoundHarvestListener(
            TaskExecutionEnvironment environment,
            HarvestAdmission admission,
            String openTip,
            AdmissionRule rule,
            InstantSource clock,
            Duration minInterval,
            MidRoundPollContext context) {
        this.environment = environment;
        this.admission = admission;
        this.taskId = context.taskId();
        this.rule = rule;
        this.clock = clock;
        this.minInterval = minInterval;
        this.pollLog = context.logTo(log);
        this.lastObservedTip = openTip;
    }

    /**
     * Harvests, admits and pushes best-effort if the rate limit allows and the harvested tip moved
     * since the last observation; otherwise a cheap no-op. Never throws: a refused or failed
     * harvest, a quarantine the poll cannot resolve, a refused or unjudged admission and an
     * admission read cut off before git answered are each one suppressed line and no push.
     *
     * @param event the progress event that just occurred; unused beyond being the
     *     poll opportunity — tip movement, not event content, is what matters
     */
    @Override
    public void onProgress(AgentProgressEvent event) {
        Instant now = clock.instant();
        if (lastPollAt != null && Duration.between(lastPollAt, now).compareTo(minInterval) < 0) {
            return;
        }
        lastPollAt = now;

        try {
            environment.harvest();
        } catch (RuntimeException e) {
            pollLog.failed(MidRoundPollLog.Subject.HARVEST, FailureReason.of(e), e);
            return;
        }
        pollLog.recovered(MidRoundPollLog.Subject.HARVEST);

        String harvested;
        try {
            harvested = admission.quarantined(taskId);
        } catch (BranchTipUnavailableException e) {
            // The resolution established nothing, so this poll observed nothing: skipping keeps a
            // failed read out of the moved/lost vocabulary entirely (FR13).
            pollLog.failed(MidRoundPollLog.Subject.TIP, FailureReason.of(e), null);
            return;
        }
        pollLog.recovered(MidRoundPollLog.Subject.TIP);
        if (harvested.equals(lastObservedTip)) {
            return;
        }
        AdmittedRange admitted;
        try {
            admitted = admission.admit(taskId, harvested, rule);
        } catch (BranchTipUnavailableException e) {
            // A read cut off before git answered judged nothing: the same tip is judged again at
            // the next poll, like any other unverified range.
            pollLog.failed(MidRoundPollLog.Subject.ADMISSION, FailureReason.of(e), null);
            return;
        }
        switch (admitted) {
            case AdmittedRange.Admitted ignored -> {
                lastObservedTip = harvested;
                pollLog.recovered(MidRoundPollLog.Subject.ADMISSION);
                admission.pushAdmitted(taskId);
            }
            case AdmittedRange.Refused refused -> {
                lastObservedTip = harvested;
                pollLog.failed(
                        MidRoundPollLog.Subject.ADMISSION, refused.reason().forLog(), null);
            }
            // Only a verdict settles a tip: an unjudged range stays unobserved, so the next poll
            // judges it again instead of waiting for the gnome's next commit.
            case AdmittedRange.Unverified unverified ->
                pollLog.failed(
                        MidRoundPollLog.Subject.ADMISSION, unverified.evidence().forLog(), null);
        }
    }
}
