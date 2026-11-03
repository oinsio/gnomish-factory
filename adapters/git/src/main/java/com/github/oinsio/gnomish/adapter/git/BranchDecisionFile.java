package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.agent.DecisionRequestRead;
import com.github.oinsio.gnomish.app.port.agent.RoundDecisionRequest;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.util.Map;
import java.util.Optional;

/**
 * The in-branch decision-file protocol for git modes (FR23, design D17): the
 * decision request lives at {@code
 * .gnomish-task/decisions/<stage>-a<attempt>-<token>.json} <em>inside the working
 * copy</em> — the single gnome-writable path under {@code .gnomish-task/}
 * (the admission's carve-out, {@link GnomeRangeInspection}, names exactly this path) — instead
 * of the host temp directory of {@code DecisionFileTransport}, which the
 * git-less in-place mode keeps. Riding the snapshot/salvage commit, a pending
 * escalation survives any death and any instance resumes it from the branch
 * alone; the file reaches the factory over the hardened harvest path and is
 * visible in the PR during escalation.
 *
 * <p>The {@code $GNOMISH_DECISION_FILE} value is the working-copy-relative
 * path: every adapter runs the agent with the working copy as its working
 * directory, and both adapters' file channels anchor relative paths there, so
 * no adapter-private absolute path leaks into the protocol. A request is live
 * only under its round's token (design D10 of make-checkpoint-gate-durable): a
 * stage and attempt repeat across visits of a stage, so each round reads exactly
 * the {@code <stage>-a<attempt>-<token>} name its token fixes and nothing else.
 * The outcome-clearing commits remove {@code .gnomish-task/decisions/} as
 * hygiene (FR14), but no read relies on that removal — the token match alone
 * decides liveness.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.adapter.agent.DecisionFileTransport} (no
 * shared classpath between {@code adapters/git} and {@code adapters/agent}): both spell the
 * {@code GNOMISH_DECISION_FILE} variable name the executor prompt tells the agent to write to.
 * The read is not part of the pair: both ends read through {@link DecisionRequestRead} over the
 * executor contract's {@code ResultFileReader}, its one owner. Each end keeps its own staleness
 * rule on purpose — this one reads only the path its round token names.
 *
 * <p>Implements FR23 of add-sandbox-core; FR13, FR16 of make-checkpoint-gate-durable; FR6 of
 * define-executor-contract.
 */
public final class BranchDecisionFile {

    /**
     * Same contract as {@code DecisionFileTransport}'s variable — the one name
     * the executor prompt instructs the agent to write to.
     */
    static final String ENV_VAR = "GNOMISH_DECISION_FILE";

    private BranchDecisionFile() {}

    /**
     * Opens one round's in-branch decision transport over {@code environment}.
     *
     * @param environment the task's bound environment; the boundary-time read runs through it
     * @param key the round's key; with {@code token}, fixes the file name
     * @param token the round's token (FR13 of make-checkpoint-gate-durable); the one path read is
     *     named by it, so a file under any other token — carried over on the tip, left by a killed
     *     round, or written beside the real one — is never read
     * @return the round's handle; never null
     */
    public static Handle open(TaskExecutionEnvironment environment, AttemptKey key, RoundToken token) {
        return new Handle(environment, GnomeRangeInspection.decisionPath(key, token));
    }

    /** One round's in-branch decision transport: the path, its env fragment, and the boundary-time read. */
    public static final class Handle {

        private final TaskExecutionEnvironment environment;
        private final String relativePath;

        private Handle(TaskExecutionEnvironment environment, String relativePath) {
            this.environment = environment;
            this.relativePath = relativePath;
        }

        /** The working-copy-relative decision path, e.g. {@code .gnomish-task/decisions/implement-a1-<token>.json}. */
        public String relativePath() {
            return relativePath;
        }

        /** The env fragment naming the decision path for the launched agent process. */
        public Map<String, String> envFragment() {
            return Map.of(ENV_VAR, relativePath);
        }

        /**
         * Reads this round's decision request through the environment channel at the round
         * boundary — after the agent process exited, before persist — through the one tolerant
         * {@link DecisionRequestRead} every medium shares, so the cap ({@code
         * ResultFileReader.DECISION_FILE_CAP_BYTES}) and the fallbacks are the host transport's
         * own (FR6 of define-executor-contract). An absent file (the agent finished without
         * asking) is empty; a file under any other name is never read.
         *
         * @return the request, if the agent wrote this round's decision file
         */
        public Optional<RoundDecisionRequest> read() {
            return DecisionRequestRead.read(limit -> environment.readFile(relativePath, limit));
        }
    }
}
