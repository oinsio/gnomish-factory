package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Notices a gnome commit landing mid-round and triggers a best-effort push of the task branch
 * (FR11's mid-round half; design D11): the agent-cli live loop delivers one {@link
 * AgentProgressEvent} per recognized tool-call/round event, and each delivery is this listener's
 * cheap opportunity to check whether {@code HEAD} moved since it last looked — a moved tip means
 * the gnome committed through a Bash tool between two events, so the branch is pushed the same
 * way a round-boundary commit is (reusing {@link BestEffortPush} and {@link RoundBoundaryCheck}
 * verbatim, never duplicating their precondition or push logic).
 *
 * <p><b>Lifecycle: one instance per round.</b> A fresh {@link MidRoundPushListener} is meant to be
 * constructed right before one {@code CliStageExecutor.execute()} call (one round) and discarded
 * right after — mirroring how {@code com.github.oinsio.gnomish.adapter.agent.StreamJsonParser
 * #parse} itself is invoked once per round (module {@code :adapters:agent}, out of this module's
 * reach). At construction the "last observed tip" baseline is
 * read from {@code HEAD} (i.e. the tip the round started at); every time this listener notices and
 * acts on a movement, the baseline advances to the new {@code HEAD} so a later event covering the
 * same still-unmoved tip is a no-op. This tracking is deliberately separate from {@link
 * GitAttemptPersistence}'s own {@code previousTip} bookkeeping: the two are different observers of
 * the same branch, at different points in the round's timeline (per-tool-event here, versus once
 * at the round's close there).
 *
 * <p>Per {@link AgentProgressListener}'s contract, {@link #onProgress} never throws and returns
 * promptly: the only git work it does is one cheap {@code rev-parse} per event, escalating to the
 * already-bounded, already-synchronous {@link BestEffortPush#pushBestEffort} only on an actual tip
 * change. Wiring this listener into a running {@code CliStageExecutor} (e.g. via {@code
 * com.github.oinsio.gnomish.adapter.agent.CompositeAgentProgressListener}) is section 4's job, not
 * this class's.
 *
 * <p>An observation is only ever a fact when git said so (FR13 of harden-logging-observability).
 * This is the <b>read-only poll</b> reading of a tip resolution, not the durable-baseline one: a
 * {@code rev-parse} that fails or prints no ref <b>skips the observation</b> — the tip is reported
 * neither moved nor lost, and no push rests on it — instead of reducing to the empty string, which
 * differs from every real SHA and so would read as movement on one event and as a return to it on
 * the next. It therefore reads {@link RoundBoundaryCheck#readHead()} rather than {@link
 * RoundBoundaryCheck#currentHead()}, whose refusal belongs to the round baseline that is recorded
 * durably. The poll runs once per progress event, so a resolution that keeps failing reports to a
 * {@link RepeatSuppressor} through {@link MidRoundPollLog} and only the edges are logged (FR4) —
 * the same treatment its sandboxed twin {@link MidRoundHarvestListener} gives the same subject.
 *
 * <p>Kept in sync with {@link MidRoundHarvestListener}: both must poll the tip once per progress
 * event through {@link VerifiedTip} (a failed resolution skips the observation, never reduces to
 * a blank), report the failure streak edge-only through {@link MidRoundPollLog}, live one
 * instance per round, and push only behind an ancestry-proving precondition — the {@link BestEffortPush} ancestry pre-check
 * here, the completed fast-forward harvest there.
 *
 * <p>Implements FR11 of add-git-workflow, coordinated with {@link AgentProgressListener} of
 * add-agent-executor; FR4, FR13 of harden-logging-observability.
 */
public final class MidRoundPushListener implements AgentProgressListener {

    private static final Logger log = LoggerFactory.getLogger(MidRoundPushListener.class);

    private final BestEffortPush push;
    private final RoundBoundaryCheck roundBoundaryCheck;
    private final String taskId;
    private final String stage;
    private final int round;
    private final Path worktreeRoot;
    private final String branch;
    private final MidRoundPollLog pollLog;

    private @Nullable String lastObservedTip;

    /**
     * @param runner the git subprocess runner, shared with the round's other git-adapter machinery
     * @param worktreeRoot the task worktree; git commands run with this path as {@code cwd}
     * @param taskId the task whose branch is being watched, for WARN log context
     * @param stage the current stage name, for WARN log context
     * @param round the current round number, for WARN log context
     * @param branch the task branch name, e.g. {@link TaskIdSanitizer#branchName}
     * @param suppressor the edge-logging owner for this round's tip-resolution failure streak
     */
    public MidRoundPushListener(
            GitProcessRunner runner,
            Path worktreeRoot,
            String taskId,
            String stage,
            int round,
            String branch,
            RepeatSuppressor suppressor) {
        this.push = new BestEffortPush(runner);
        this.roundBoundaryCheck = new RoundBoundaryCheck(runner, worktreeRoot, branch);
        this.worktreeRoot = worktreeRoot;
        this.taskId = taskId;
        this.stage = stage;
        this.round = round;
        this.branch = branch;
        this.pollLog = new MidRoundPollLog(log, suppressor, taskId, branch);
        // An unresolvable HEAD leaves the baseline unknown rather than blank: the first event that
        // does resolve it adopts it silently, and only movement away from that adopted tip pushes.
        this.lastObservedTip = VerifiedTip.read(roundBoundaryCheck.readHead()).orElse(null);
    }

    /**
     * Checks whether {@code HEAD} moved since the last observation and, if so, delegates to {@link
     * BestEffortPush#pushBestEffort} using the previously observed tip as the ancestry baseline,
     * then advances the baseline to the new tip. A stationary tip is a no-op beyond the one cheap
     * {@code rev-parse}. Never throws (design D10's listener contract): a resolution that fails is
     * one suppressed line and no push, and an adopted first baseline is no push either — the
     * ancestry precondition {@link BestEffortPush} enforces has nothing to rest on until one
     * observation has been made.
     *
     * @param event the progress event that just occurred; unused beyond triggering the check, since
     *     tip movement — not the event's own content — is what this listener reacts to
     */
    @Override
    public void onProgress(AgentProgressEvent event) {
        GitCommandResult read = roundBoundaryCheck.readHead();
        Optional<String> tip = VerifiedTip.read(read);
        if (tip.isEmpty()) {
            // The resolution established nothing, so this event observed nothing: skipping keeps a
            // failed read out of the moved/unmoved vocabulary entirely (FR13).
            pollLog.failed(MidRoundPollLog.Subject.TIP, VerifiedTip.failureReason(read), null);
            return;
        }
        pollLog.recovered(MidRoundPollLog.Subject.TIP);

        String currentTip = tip.get();
        String previousTip = lastObservedTip;
        lastObservedTip = currentTip;
        if (previousTip == null || currentTip.equals(previousTip)) {
            return;
        }
        push.pushBestEffort(taskId, stage, round, worktreeRoot, branch, roundBoundaryCheck, previousTip);
    }
}
