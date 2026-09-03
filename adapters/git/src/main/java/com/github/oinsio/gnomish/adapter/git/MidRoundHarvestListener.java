package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.logtext.FailureReason;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sandboxed twin of {@link MidRoundPushListener} (FR5, git-task-persistence
 * "Best-effort push"): notices a gnome commit landing mid-round <em>inside the
 * environment</em> and mirrors it out — harvest first, then a best-effort push.
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
 * <p>Push preconditions differ from the host listener by design: the
 * fast-forward-only harvest itself proves ancestry, so a completed harvest is
 * the precondition — a refused or failed harvest skips the push with one WARN
 * and leaves the authoritative verdict to the round-boundary check
 * (git-task-persistence "Push safety rules"). Like every {@link
 * AgentProgressListener}, {@link #onProgress} never throws.
 *
 * <p>A poll that keeps failing would otherwise cost one WARN per progress event for the whole
 * round, so failures report to a {@link RepeatSuppressor} through {@link MidRoundPollLog}, which
 * logs only the edges (FR4 of harden-logging-observability). The host-mode twin of this listener,
 * {@link MidRoundPushListener}, polls once per progress event too, and suppresses its own tip
 * subject the same way.
 *
 * <p>An observation is only ever a fact when git said so (FR13): a tip resolution that fails or
 * prints no ref <b>skips the observation</b> — the tip is reported neither moved nor lost, and no
 * push decision rests on it — rather than reducing to the empty string, which differs from every
 * real SHA and so would read as movement on one poll and as a return to it on the next.
 *
 * <p><b>Lifecycle: one instance per round</b>, constructed right before the
 * round's {@code execute()} and discarded after, mirroring {@link
 * MidRoundPushListener}. The tip baseline starts at the factory clone's branch
 * tip at construction.
 *
 * <p>Kept in sync with {@link MidRoundPushListener}: both must poll the tip once per progress
 * event through {@link VerifiedTip} (a failed resolution skips the observation, never reduces to
 * a blank), report the failure streak edge-only through {@link MidRoundPollLog}, live one
 * instance per round, and push only behind an ancestry-proving precondition — the completed fast-forward harvest
 * here, the {@link BestEffortPush} ancestry pre-check there.
 *
 * <p>Implements FR5 of add-sandbox-core; coordinates with FR11 of
 * add-git-workflow.
 */
public final class MidRoundHarvestListener implements AgentProgressListener {

    private static final Logger log = LoggerFactory.getLogger(MidRoundHarvestListener.class);

    private final TaskExecutionEnvironment environment;
    private final GitProcessRunner runner;
    private final Path cloneDir;
    private final BranchPush push;
    private final String branch;
    private final Clock clock;
    private final Duration minInterval;
    private final MidRoundPollLog pollLog;

    private @Nullable Instant lastPollAt;
    private @Nullable String lastObservedTip;

    /**
     * @param environment the task's bound environment; {@code harvest()} is the only call made
     * @param runner the git subprocess runner, shared with the run's other git-adapter machinery
     * @param cloneDir the factory clone harvest lands in; tip reads and the push run here
     * @param clock the poll rate-limit time source
     * @param minInterval the minimum time between two actual harvests; the factory-side rate
     *     limit of design D3
     * @param context whose round this is — the task id and branch (e.g. {@link
     *     TaskIdSanitizer#branchName}) and the suppressor its failure streaks live in
     */
    public MidRoundHarvestListener(
            TaskExecutionEnvironment environment,
            GitProcessRunner runner,
            Path cloneDir,
            Clock clock,
            Duration minInterval,
            MidRoundPollContext context) {
        this.environment = environment;
        this.runner = runner;
        this.cloneDir = cloneDir;
        this.push = new BranchPush(runner);
        this.branch = context.branch();
        this.clock = clock;
        this.minInterval = minInterval;
        this.pollLog = context.logTo(log);
        this.lastObservedTip = currentTip().orElse(null);
    }

    /**
     * Harvests and pushes best-effort if the rate limit allows and the harvested
     * tip moved since the last observation; otherwise a cheap no-op. Never
     * throws: a refused or failed harvest, and a tip the poll cannot resolve,
     * are each one suppressed line and no push.
     *
     * @param event the progress event that just occurred; unused beyond being the
     *     poll opportunity — tip movement, not event content, is what matters
     */
    @Override
    public void onProgress(AgentProgressEvent event) {
        Instant now = clock.now();
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

        GitCommandResult read = readTip();
        Optional<String> tip = VerifiedTip.read(read);
        if (tip.isEmpty()) {
            // The resolution established nothing, so this poll observed nothing: skipping keeps a
            // failed read out of the moved/lost vocabulary entirely (FR13).
            pollLog.failed(MidRoundPollLog.Subject.TIP, VerifiedTip.failureReason(read), null);
            return;
        }
        pollLog.recovered(MidRoundPollLog.Subject.TIP);
        String observedTip = tip.get();
        if (observedTip.equals(lastObservedTip)) {
            return;
        }
        lastObservedTip = observedTip;
        push.pushBestEffort(cloneDir, branch);
    }

    /**
     * The last observed tip, or empty when the round started without a resolvable one — an unknown
     * baseline, deliberately not a remembered blank: the first poll that does resolve the tip adopts
     * it, and pushes, since a best-effort push of an unmoved branch is a no-op while a missed one
     * leaves in-box commits unmirrored.
     */
    private Optional<String> currentTip() {
        return VerifiedTip.read(readTip());
    }

    private GitCommandResult readTip() {
        return runner.run(cloneDir, "rev-parse", "--verify", "refs/heads/" + branch);
    }
}
