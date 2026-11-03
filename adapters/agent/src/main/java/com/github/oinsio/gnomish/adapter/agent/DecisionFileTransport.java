package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.app.port.agent.DecisionRequestRead;
import com.github.oinsio.gnomish.app.port.agent.RoundDecisionRequest;
import com.github.oinsio.gnomish.executorcontract.ResultSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Owns the per-round decision-file temp-directory lifecycle (design D1):
 * create a fresh directory outside the stage workspace before the agent
 * process spawns, expose the decision-file path and its {@code
 * $GNOMISH_DECISION_FILE} env-var fragment for the caller to wire into the
 * process's environment, read back the raw file content after the process
 * exits, then delete the directory. A stale file from a prior round is
 * impossible by construction: every {@link #open()} call gets its own fresh
 * temp directory, and {@link Handle#readAndClose()} removes it (NFR-R3); the
 * directory never lives under the workspace, so nothing from the decision
 * protocol can leak into a future task branch (NFR-S2).
 *
 * <p>This class does not interpret the file's content: the read goes through the one tolerant
 * {@link DecisionRequestRead} every decision-file medium shares, over the executor contract's
 * result reader (FR6 of define-executor-contract) — capped at {@code
 * ResultFileReader.DECISION_FILE_CAP_BYTES}, a symbolic link at the path refused rather than
 * followed, a file over the cap cut with the reader's truncation marker. {@link
 * Handle#readAndClose()} returns the request when the agent wrote the file, or {@link
 * Optional#empty()} when it never did — the file absent case that maps to {@code Completed}
 * downstream. Removing the directory afterwards is this transport's own cleanup, never the
 * reader's.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.adapter.git.BranchDecisionFile} (no
 * shared classpath between {@code adapters/agent} and {@code adapters/git}): both spell the
 * {@code GNOMISH_DECISION_FILE} variable name the executor prompt tells the agent to write to.
 * The read is not part of the pair: both ends read through {@link DecisionRequestRead} over the
 * executor contract's {@code ResultFileReader}, its one owner. Each end keeps its own staleness
 * rule on purpose — this one deletes its per-round temp directory after the read.
 *
 * <p>Implements FR3, NFR-R3, NFR-S2, D1 of add-agent-executor; FR6 of define-executor-contract.
 */
public final class DecisionFileTransport {

    private static final String ENV_VAR = "GNOMISH_DECISION_FILE";

    private static final String DECISION_FILE_NAME = "decision.json";

    private static final String TEMP_DIR_PREFIX = "gnomish-decision-";

    private final @Nullable Path tempParent;

    /**
     * Production transport: per-round directories are created under the JVM
     * temp directory ({@code java.io.tmpdir}), always outside any stage
     * workspace (NFR-S2).
     */
    public DecisionFileTransport() {
        this(null);
    }

    /**
     * Testing seam (package-private): the same transport rooted at a caller-
     * supplied parent directory instead of the shared, concurrently written
     * JVM temp directory, so a spec can assert the create/discard lifecycle in
     * an isolated location. Production always uses {@link
     * #DecisionFileTransport()} ({@code null} parent → JVM temp).
     *
     * @param tempParent the directory each round's fresh temp dir is created
     *     under, or {@code null} to use the JVM temp directory; must exist
     */
    DecisionFileTransport(@Nullable Path tempParent) {
        this.tempParent = tempParent;
    }

    /**
     * Creates a fresh per-round temp directory outside the workspace and
     * resolves the decision-file path inside it. The directory is created
     * eagerly so a caller can wire {@link Handle#envFragment()} into the
     * process's environment before spawning; the file itself is written by
     * the agent, not this class.
     *
     * <p>Implements FR3, NFR-S2, D1 of add-agent-executor.
     *
     * @return a handle over the fresh temp directory and decision-file path;
     *     never null
     * @throws UncheckedIOException if the temp directory cannot be created
     */
    public Handle open() {
        try {
            Path tempDir = tempParent == null
                    ? Files.createTempDirectory(TEMP_DIR_PREFIX)
                    : Files.createTempDirectory(tempParent, TEMP_DIR_PREFIX);
            return new Handle(tempDir.resolve(DECISION_FILE_NAME));
        } catch (IOException e) {
            throw new UncheckedIOException("could not create decision-file temp directory", e);
        }
    }

    /**
     * One round's decision-file transport: the resolved file path, the
     * env-var fragment naming it, and the read-then-delete lifecycle step.
     *
     * <p>Implements FR3, NFR-R3, NFR-S2, D1 of add-agent-executor.
     */
    public static final class Handle {

        private final Path decisionFilePath;

        private Handle(Path decisionFilePath) {
            this.decisionFilePath = decisionFilePath;
        }

        /**
         * The decision-file path inside this round's fresh temp directory —
         * the value a caller must set {@code $GNOMISH_DECISION_FILE} to (also
         * available pre-packaged via {@link #envFragment()}).
         *
         * @return the resolved decision-file path; never null
         */
        public Path decisionFilePath() {
            return decisionFilePath;
        }

        /**
         * The env-var fragment to merge into the launched process's
         * environment so the agent knows where to write its decision (FR3).
         *
         * @return a single-entry map, {@code GNOMISH_DECISION_FILE} →
         *     {@link #decisionFilePath()} as a string; never null
         */
        public Map<String, String> envFragment() {
            return Map.of(ENV_VAR, decisionFilePath.toString());
        }

        /**
         * Reads the decision request, if the agent wrote one, through {@link DecisionRequestRead},
         * then deletes this round's temp directory and everything in it — the lifecycle's
         * terminal step (NFR-R3). Safe to call more than once: a directory already deleted by a
         * prior call simply yields {@link Optional#empty()} again.
         *
         * <p>Implements FR3, NFR-R3, D1 of add-agent-executor; FR6 of define-executor-contract.
         *
         * @return the request if the agent wrote the file, {@link Optional#empty()} if it never
         *     did; never null
         * @throws UncheckedIOException if the file cannot be read (a symbolic link or another
         *     non-regular file at the path included) or the directory cannot be deleted
         */
        public Optional<RoundDecisionRequest> readAndClose() {
            Optional<RoundDecisionRequest> request = DecisionRequestRead.read(ResultSource.hostFile(decisionFilePath));
            try {
                deleteRecursively(Objects.requireNonNull(decisionFilePath.getParent()));
            } catch (IOException e) {
                throw new UncheckedIOException("could not delete decision-file temp directory: " + decisionFilePath, e);
            }
            return request;
        }

        /**
         * Deletes this round's temp directory without reading the decision
         * file first — the cleanup step for infrastructure-failure paths that
         * never reach {@link #readAndClose()} (design D1). Safe to call more
         * than once, and safe to call after {@link #readAndClose()}.
         *
         * <p>Implements NFR-R3, D1 of add-agent-executor.
         *
         * @throws UncheckedIOException if the temp directory cannot be deleted
         */
        public void discard() {
            try {
                deleteRecursively(Objects.requireNonNull(decisionFilePath.getParent()));
            } catch (IOException e) {
                throw new UncheckedIOException("could not delete decision-file temp directory: " + decisionFilePath, e);
            }
        }

        private static void deleteRecursively(Path directory) throws IOException {
            if (!Files.exists(directory)) {
                return;
            }
            try (var stream = Files.walk(directory)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
