package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.adapter.agent.AgentProgressEvent;
import com.github.oinsio.gnomish.adapter.agent.AgentProgressListener;

/**
 * The {@link AgentProgressListener} that enriches the held {@link
 * Activity.Executing} with live tool detail as an executor round's CLI process
 * reports progress: {@code ToolStarted} sets {@code currentTool} and increments
 * {@code toolCalls}, {@code RoundFinished} clears both back to their round-start
 * defaults — {@code RoundStarted} is a deliberate no-op, since the engine's own
 * {@code AttemptStarted} event already set {@link Activity.Executing} through
 * {@link StatusEventListener} before the executor (and this listener) ever see a
 * round begin.
 *
 * <p>Every mutation reads the currently held activity first and only replaces it
 * when it is an {@link Activity.Executing} — the enricher never sets an activity
 * itself, only refines the one already there. This is also how "executor rounds
 * only" (FR7) is enforced without any special dispatch: a judge round runs under
 * {@link Activity.Verifying}, not {@code Executing}, so every method here is a
 * no-op for it, and likewise if no activity is currently held at all.
 *
 * <p>Implements FR7, UX1, D10, D12 of add-agent-executor.
 */
public final class AgentActivityEnricher implements AgentProgressListener {

    private final StatusSnapshotHolder holder;

    /**
     * Wraps {@code holder}, the snapshot this enricher reads the current activity
     * from and writes the refined one back onto.
     *
     * @param holder the snapshot holder to enrich; never null
     */
    public AgentActivityEnricher(StatusSnapshotHolder holder) {
        this.holder = holder;
    }

    /**
     * Refines the held {@link Activity.Executing}, if any, per the event kind — a
     * no-op for {@link AgentProgressEvent.RoundStarted} and for any event when the
     * current activity is not {@code Executing}.
     *
     * <p>Implements FR7, UX1, D10, D12 of add-agent-executor.
     *
     * @param event the progress event that just occurred; never null
     */
    @Override
    public void onProgress(AgentProgressEvent event) {
        switch (event) {
            case AgentProgressEvent.RoundStarted ignored -> {
                // AttemptStarted already set Executing before the round began (D10).
            }
            case AgentProgressEvent.ToolStarted started -> onToolStarted(started.name());
            case AgentProgressEvent.RoundFinished ignored -> onRoundFinished();
        }
    }

    private void onToolStarted(String toolName) {
        if (holder.activity().activity() instanceof Activity.Executing executing) {
            holder.updateActivity(new Activity.Executing(executing.since(), toolName, executing.toolCalls() + 1));
        }
    }

    private void onRoundFinished() {
        if (holder.activity().activity() instanceof Activity.Executing executing) {
            holder.updateActivity(new Activity.Executing(executing.since()));
        }
    }
}
