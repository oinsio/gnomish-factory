package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.agent.AgentProgressEvent;
import com.github.oinsio.gnomish.adapter.agent.AgentProgressListener;
import java.nio.file.Path;

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
 * right after — mirroring how {@link com.github.oinsio.gnomish.adapter.agent.StreamJsonParser
 * #parse} itself is invoked once per round. At construction the "last observed tip" baseline is
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
 * change. Wiring this listener into a running {@code CliStageExecutor} (e.g. via {@link
 * com.github.oinsio.gnomish.adapter.agent.CompositeAgentProgressListener}) is section 4's job, not
 * this class's.
 *
 * <p>Implements FR11 of add-git-workflow, coordinated with {@link AgentProgressListener} of
 * add-agent-executor.
 */
public final class MidRoundPushListener implements AgentProgressListener {

    private final BestEffortPush push;
    private final RoundBoundaryCheck roundBoundaryCheck;
    private final String taskId;
    private final String stage;
    private final int round;
    private final Path worktreeRoot;
    private final String branch;

    private String lastObservedTip;

    /**
     * @param runner the git subprocess runner, shared with the round's other git-adapter machinery
     * @param worktreeRoot the task worktree; git commands run with this path as {@code cwd}
     * @param taskId the task whose branch is being watched, for WARN log context
     * @param stage the current stage name, for WARN log context
     * @param round the current round number, for WARN log context
     * @param branch the task branch name, e.g. {@link TaskIdSanitizer#branchName}
     */
    public MidRoundPushListener(
            GitProcessRunner runner, Path worktreeRoot, String taskId, String stage, int round, String branch) {
        this.push = new BestEffortPush(runner);
        this.roundBoundaryCheck = new RoundBoundaryCheck(runner, worktreeRoot, branch);
        this.worktreeRoot = worktreeRoot;
        this.taskId = taskId;
        this.stage = stage;
        this.round = round;
        this.branch = branch;
        this.lastObservedTip = roundBoundaryCheck.currentHead();
    }

    /**
     * Checks whether {@code HEAD} moved since the last observation and, if so, delegates to {@link
     * BestEffortPush#pushBestEffort} using the previously observed tip as the ancestry baseline,
     * then advances the baseline to the new tip. A stationary tip is a no-op beyond the one cheap
     * {@code rev-parse}. Never throws (design D10's listener contract).
     *
     * @param event the progress event that just occurred; unused beyond triggering the check, since
     *     tip movement — not the event's own content — is what this listener reacts to
     */
    @Override
    public void onProgress(AgentProgressEvent event) {
        String currentTip = roundBoundaryCheck.currentHead();
        if (currentTip.equals(lastObservedTip)) {
            return;
        }

        String previousTip = lastObservedTip;
        push.pushBestEffort(taskId, stage, round, worktreeRoot, branch, roundBoundaryCheck, previousTip);
        lastObservedTip = currentTip;
    }
}
