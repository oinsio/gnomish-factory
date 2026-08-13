package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.environment.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * The in-branch decision-file protocol for git modes (FR23, design D17): the
 * decision request lives at {@code
 * .gnomish-task/decisions/<stage>-a<attempt>.json} <em>inside the working
 * copy</em> — the single gnome-writable path under {@code .gnomish-task/}
 * ({@link HarvestedBoundaryCheck}'s carve-out names exactly this path) — instead
 * of the host temp directory of {@code DecisionFileTransport}, which the
 * git-less in-place mode keeps. Riding the snapshot/salvage commit, a pending
 * escalation survives any death and any instance resumes it from the branch
 * alone; the file reaches the factory over the hardened harvest path and is
 * visible in the PR during escalation.
 *
 * <p>The {@code $GNOMISH_DECISION_FILE} value is the working-copy-relative
 * path: every adapter runs the agent with the working copy as its working
 * directory, and both adapters' file channels anchor relative paths there, so
 * no adapter-private absolute path leaks into the protocol. Stale files are
 * self-excluding (FR23): each round reads exactly its own
 * {@code <stage>-a<attempt>} name and nothing else. There is no eager removal —
 * the Completed cleanup commit strips {@code .gnomish-task/} from the tip.
 *
 * <p>Implements FR23 of add-sandbox-core.
 */
public final class BranchDecisionFile {

    /**
     * Same contract as {@code DecisionFileTransport}'s variable — the one name
     * the executor prompt instructs the agent to write to.
     */
    static final String ENV_VAR = "GNOMISH_DECISION_FILE";

    /** A decision request is a small JSON question; 1 MiB is generous (NFR-S3 read discipline). */
    private static final long SIZE_CAP = 1L << 20;

    private BranchDecisionFile() {}

    /**
     * Opens one round's in-branch decision transport over {@code environment}.
     *
     * @param environment the task's bound environment; the boundary-time read runs through it
     * @param key the round's key; fixes the file name and excludes stale files
     * @return the round's handle; never null
     */
    public static Handle open(TaskExecutionEnvironment environment, AttemptKey key) {
        return new Handle(environment, HarvestedBoundaryCheck.decisionPath(key));
    }

    /** One round's in-branch decision transport: the path, its env fragment, and the boundary-time read. */
    public static final class Handle {

        private final TaskExecutionEnvironment environment;
        private final String relativePath;

        private Handle(TaskExecutionEnvironment environment, String relativePath) {
            this.environment = environment;
            this.relativePath = relativePath;
        }

        /** The working-copy-relative decision path, e.g. {@code .gnomish-task/decisions/implement-a1.json}. */
        public String relativePath() {
            return relativePath;
        }

        /** The env fragment naming the decision path for the launched agent process. */
        public Map<String, String> envFragment() {
            return Map.of(ENV_VAR, relativePath);
        }

        /**
         * Reads the raw decision content through the environment channel at the
         * round boundary — after the agent process exited, before persist. An
         * absent file (the agent finished without asking) is empty; a file
         * under any other name is never read.
         *
         * @return the raw content if the agent wrote this round's decision file
         */
        public Optional<String> read() {
            return environment.readFile(relativePath, SIZE_CAP).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
        }
    }
}
