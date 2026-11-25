package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.port.agent.RoundHold;
import com.github.oinsio.gnomish.app.port.agent.TerminalClose;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.nio.file.Path;
import java.util.Map;

/**
 * One box round of {@link SandboxRoundEnvironmentSource}, from its open to its close or discard
 * (design D14 of extract-agent-round-runner, as amended 2026-11-25): the stage's leased box, the
 * in-branch answer file named after the round's token, the mid-round harvest listener, and the
 * in-box snapshot step every snapshot of the round goes through. Going {@code pending} registers the
 * pending-round snapshot and keeps the round held by its source, so a poll runs in this very round;
 * the terminal close registers the round's snapshot under its {@link TerminalClose} and, with the
 * discard, lets the source's hold go.
 *
 * <p>Implements FR4, FR5, FR21, FR23 of add-sandbox-core; FR10 of extract-agent-round-runner.
 */
final class SandboxRound implements RoundEnvironmentSource.Round {

    private final TaskExecutionEnvironment environment;
    private final BranchDecisionFile.Handle decision;
    private final MidRoundHarvestListener midRound;
    private final AttemptKey key;
    private final EnvironmentRoundSnapshot snapshot;
    private final RoundHold<SandboxRound> hold;

    /**
     * @param environment the stage's leased box; never null
     * @param decision the round's in-branch answer file; never null
     * @param midRound the round's mid-round harvest listener; never null
     * @param key the round's attempt key; never null
     * @param snapshot the in-box snapshot step over the run's cell; never null
     * @param hold the source's hold of its open round, which this round is in; never null
     */
    SandboxRound(
            TaskExecutionEnvironment environment,
            BranchDecisionFile.Handle decision,
            MidRoundHarvestListener midRound,
            AttemptKey key,
            EnvironmentRoundSnapshot snapshot,
            RoundHold<SandboxRound> hold) {
        this.environment = environment;
        this.decision = decision;
        this.midRound = midRound;
        this.key = key;
        this.snapshot = snapshot;
        this.hold = hold;
    }

    @Override
    public TaskExecutionEnvironment environment() {
        return environment;
    }

    @Override
    public Path answerFilePath() {
        // Working-copy-relative by protocol (D17): the agent runs with the working copy as
        // its cwd in every adapter, so the relative path is the correct Write-allowance form.
        return Path.of(decision.relativePath());
    }

    @Override
    public Map<String, String> answerEnvFragment() {
        return decision.envFragment();
    }

    @Override
    public AgentProgressListener roundListener() {
        return midRound;
    }

    @Override
    public void goPending() {
        // The pending-round snapshot and its harvest into the cell; the round stays held (D11).
        snapshot(RoundClose.PENDING);
    }

    @Override
    public void closeRound(TerminalClose close) {
        hold.release(this);
        snapshot(close.snapshot());
    }

    @Override
    public void discard() {
        // The in-branch answer file holds no round-scoped host state to clean up: a
        // pending file either rides a later snapshot/salvage commit or dies with the box.
        hold.release(this);
    }

    private void snapshot(RoundClose close) {
        snapshot.snapshot(key.taskId(), key.stage(), key.attempt(), close);
    }
}
