package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateEgressCursorDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.adapter.git.state.TraceLineWriter;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.ToolCall;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The sandboxed realization of the engine's {@link AttemptPersistence} port
 * (FR21, FR22, design D15, D16): where host mode closes a round as one worktree
 * commit ({@link GitAttemptPersistence}), a sandboxed round closes in two steps
 * — the executor's snapshot commit ({@link EnvironmentRoundSnapshot}, already
 * harvested and verified by the time this port is called) and this class's
 * <b>state commit</b>: {@code state.json} and the round trace written through
 * the environment channel ({@code putFile}), committed in-box (hooks off at
 * argv level), harvested, and then verified at the boundary.
 *
 * <p>Harvest-boundary integrity (D16), all factory-side and trusted:
 *
 * <ul>
 *   <li><b>boundary protocol</b> — {@code .gnomish-task/} untouched by the gnome
 *       between the previous tip and the snapshot commit, with the single
 *       decision-file carve-out ({@link HarvestedBoundaryCheck}, FR23);
 *   <li><b>parent-check</b> — the harvested state commit's parent must be the
 *       snapshot commit; a daemon-inserted commit aborts;
 *   <li><b>read-back</b> — the harvested {@code state.json} and trace must be
 *       byte-identical to what the factory wrote (bare-object reads via {@link
 *       GitObjects}, no checkout, no hooks); in-box tampering between {@code
 *       putFile} and the commit aborts.
 * </ul>
 *
 * <p>Any mismatch throws {@link RoundBoundaryViolationException}; the engine
 * turns a thrown persist into {@code Aborted}, the branch keeps the evidence,
 * and the environment is kept untouched. This is a strict port: any failure to
 * durably commit throws, never returns.
 *
 * <p>Implements FR21, FR22, FR23 of add-sandbox-core.
 */
public final class EnvironmentAttemptPersistence implements AttemptPersistence {

    private static final String STATE_PATH = ".gnomish-task/state.json";

    // Paths and the commit message travel as positional args ($1-$3), never string-interpolated
    // into the script, so neither can carry a shell metacharacter that alters the command —
    // the same defense-in-depth pattern ContainerFileChannel uses for factory-authored content.
    private static final String COMMIT_SCRIPT = "git add -- \"$1\" \"$2\" && git -c core.hooksPath= commit -m \"$3\"";

    private final TaskExecutionEnvironment environment;
    private final GitProcessRunner runner;
    private final Path cloneDir;
    private final GitObjects gitObjects;
    private final String branch;
    private final AttemptCommitRef attemptCommit;
    private final HarvestedBoundaryCheck boundaryCheck;
    private String previousTip;

    /**
     * @param environment the task's bound environment; state files and the state commit cross it
     * @param runner the git subprocess runner for factory-side ref reads
     * @param cloneDir the factory clone harvest lands in
     * @param gitObjects the bare-object facade opened against the factory clone, for byte-exact
     *     read-back (D16)
     * @param taskId the tracker's original taskId; sanitized into the task branch name
     * @param attemptCommit the run's attempt-commit ref, recorded by the snapshot step
     */
    public EnvironmentAttemptPersistence(
            TaskExecutionEnvironment environment,
            GitProcessRunner runner,
            Path cloneDir,
            GitObjects gitObjects,
            String taskId,
            AttemptCommitRef attemptCommit) {
        this.environment = environment;
        this.runner = runner;
        this.cloneDir = cloneDir;
        this.gitObjects = gitObjects;
        this.branch = TaskIdSanitizer.branchName(taskId);
        this.attemptCommit = attemptCommit;
        this.boundaryCheck = new HarvestedBoundaryCheck(runner, cloneDir);
        this.previousTip = currentTip();
    }

    @Override
    public void persist(String taskId, TaskState state, ToolTrace trace) {
        AttemptKey key = trace.key();
        String snapshot = attemptCommit.required();

        boundaryCheck.verify(taskId, previousTip, snapshot, key);

        byte[] stateBytes = renderState(taskId, key, state);
        byte[] traceBytes = renderTrace(trace);
        String tracePath = ".gnomish-task/" + TraceLineWriter.relativePath(key);
        environment.putFile(STATE_PATH, stateBytes);
        environment.putFile(tracePath, traceBytes);

        commitInBox(taskId, key, tracePath);
        environment.harvest();

        String tip = currentTip();
        verifyParent(taskId, tip, snapshot);
        readBack(taskId, tip, STATE_PATH, stateBytes);
        readBack(taskId, tip, tracePath, traceBytes);

        previousTip = tip;
    }

    private void commitInBox(String taskId, AttemptKey key, String tracePath) {
        // Only the two factory files are staged: gnome residue outside .gnomish-task/ belongs to
        // the next round (or salvage), never to the state commit (D15).
        String message = ServiceCommitMessages.round(key.stage(), key.attempt());
        List<String> argv = List.of("sh", "-c", COMMIT_SCRIPT, "gnomish", STATE_PATH, tracePath, message);
        ExecHandle handle = environment.exec(new ExecCommand(argv, Map.of(), null, true));
        String output = readFully(handle.output());
        int exitCode = handle.waitForExit();
        if (exitCode != 0) {
            throw new GitPersistFailedException(taskId, key.stage(), key.attempt(), "in-box state commit", output);
        }
    }

    private void verifyParent(String taskId, String tip, String snapshot) {
        String parent = gitObjects
                .resolveRef(tip + "^")
                .map(ObjectId::hex)
                .orElseThrow(() -> new RoundBoundaryViolationException(
                        taskId, "harvested state commit " + tip + " has no readable parent"));
        if (!parent.equals(snapshot)) {
            throw new RoundBoundaryViolationException(
                    taskId,
                    "harvested state commit's parent " + parent + " is not the snapshot commit " + snapshot
                            + " (a commit was inserted inside the environment)");
        }
    }

    private void readBack(String taskId, String tip, String path, byte[] written) {
        byte[] harvested;
        try {
            // Cap = written length + 1: byte-identical content fits exactly, and any longer
            // in-box replacement trips the cap and lands in the violation below.
            harvested = gitObjects.readBlob(gitObjects.resolveRef(tip).orElseThrow(), path, written.length + 1L);
        } catch (RuntimeException e) {
            throw new RoundBoundaryViolationException(taskId, "read-back of " + path + " failed: " + e);
        }
        if (!Arrays.equals(harvested, written)) {
            throw new RoundBoundaryViolationException(
                    taskId,
                    path + " harvested from the environment differs from what the factory wrote (in-box"
                            + " tampering)");
        }
    }

    /**
     * Renders {@code state.json} for this round, carrying the environment's denial
     * cursor (FR5 of fix-denial-report-attachment): the round's denials are already
     * on the state being written, so committing the position that delimits them in
     * the same commit is what lets a resuming instance continue the delta instead of
     * replaying the guard container's whole surviving log onto its first round.
     */
    private byte[] renderState(String taskId, AttemptKey key, TaskState state) {
        StateEgressCursorDto cursor = environment
                .denialCursor()
                .map(c -> new StateEgressCursorDto(c.source(), c.position()))
                .orElse(null);
        try {
            return TaskStateJson.mapper()
                    .writeValueAsString(StateJsonMapper.toDto(state, cursor))
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GitPersistFailedException(taskId, key.stage(), key.attempt(), "serializing state.json", e);
        }
    }

    private static byte[] renderTrace(ToolTrace trace) {
        StringBuilder content = new StringBuilder();
        for (ToolCall call : trace.calls()) {
            content.append(TraceLineWriter.renderLine(call)).append('\n');
        }
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String currentTip() {
        return runner.run(cloneDir, "rev-parse", "refs/heads/" + branch)
                .stdout()
                .trim();
    }

    private static String readFully(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read in-box commit output", e);
        }
    }
}
