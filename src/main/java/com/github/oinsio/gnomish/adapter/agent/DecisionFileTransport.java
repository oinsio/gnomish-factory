package com.github.oinsio.gnomish.adapter.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
 * <p>This class does not interpret the file's content: tolerant parsing
 * (garbage → raw text becomes the question, empty file → fallback text) is
 * task 6.3's job, and mapping to {@link
 * com.github.oinsio.gnomish.domain.engine.ExecutionResult} is task 6.5's.
 * {@link Handle#readAndClose()} returns the file's raw content when present,
 * or {@link Optional#empty()} when the agent never wrote it — the file
 * absent case that maps to {@code Completed} downstream.
 *
 * <p>Implements FR3, NFR-R3, NFR-S2, D1 of add-agent-executor.
 */
public final class DecisionFileTransport {

    private static final String ENV_VAR = "GNOMISH_DECISION_FILE";

    private static final String DECISION_FILE_NAME = "decision.json";

    private static final String TEMP_DIR_PREFIX = "gnomish-decision-";

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
            Path tempDir = Files.createTempDirectory(TEMP_DIR_PREFIX);
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
         * Reads the decision file's raw content, if the agent wrote one, then
         * deletes this round's temp directory and everything in it — the
         * lifecycle's terminal step (NFR-R3). Safe to call more than once:
         * a directory already deleted by a prior call simply yields {@link
         * Optional#empty()} again.
         *
         * <p>Implements FR3, NFR-R3, D1 of add-agent-executor.
         *
         * @return the file's raw content if present, {@link Optional#empty()}
         *     if the agent never wrote it; never null
         */
        public Optional<String> readAndClose() {
            try {
                Optional<String> content = Files.isRegularFile(decisionFilePath)
                        ? Optional.of(Files.readString(decisionFilePath))
                        : Optional.empty();
                deleteRecursively(Objects.requireNonNull(decisionFilePath.getParent()));
                return content;
            } catch (IOException e) {
                throw new UncheckedIOException("could not read decision file: " + decisionFilePath, e);
            }
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
