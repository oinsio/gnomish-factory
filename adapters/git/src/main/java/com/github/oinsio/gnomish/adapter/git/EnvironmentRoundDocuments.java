package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.EgressCursorDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.adapter.git.state.TraceLineWriter;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Renders the two factory-authored documents a sandboxed round commits — {@code state.json}
 * and the round's trace file — into the exact bytes that cross the environment channel and
 * are read back byte-for-byte after the harvest ({@link HarvestedStateCommitCheck}). Keeping
 * the rendering here is what lets {@link EnvironmentAttemptPersistence} hold nothing but the
 * durable sequence: it never sees a DTO, a mapper or a charset, only the bytes it must write,
 * commit and get back unchanged.
 *
 * <p>{@code state.json} carries the environment's denial cursor (FR5 of
 * fix-denial-report-attachment): the round's denials are already on the state being written,
 * so committing the position that delimits them in the same commit is what lets a resuming
 * instance continue the delta instead of replaying the guard container's whole surviving log
 * onto its first round.
 *
 * <p>The position asked for here is the one the round's own {@code readDenials} left behind
 * (design D7 of fix-denial-attribution-durability): asking does not advance it, so the cursor
 * the commit carries delimits exactly the denials the state beside it records — it can lag
 * that record after a lost commit, never lead it.
 *
 * <p>Implements FR21 of add-sandbox-core; FR5 of fix-denial-report-attachment.
 */
final class EnvironmentRoundDocuments {

    private final TaskExecutionEnvironment environment;

    /**
     * @param environment the task's bound environment, asked for the denial cursor each round;
     *     never null
     */
    EnvironmentRoundDocuments(TaskExecutionEnvironment environment) {
        this.environment = environment;
    }

    /**
     * Renders {@code state.json} for one round, denial cursor included.
     *
     * @param taskId the task being persisted, for the failure message
     * @param key the round whose state is being rendered, for the failure message
     * @param state the round's engine state
     * @return the {@code state.json} bytes, denial cursor included
     * @throws GitPersistFailedException if the state cannot be serialized
     */
    byte[] state(String taskId, AttemptKey key, TaskState state) {
        EgressCursorDto cursor = environment
                .denialCursor()
                .map(c -> new EgressCursorDto(c.source(), c.position()))
                .orElse(null);
        try {
            return TaskStateJson.mapper()
                    .writeValueAsString(StateJsonMapper.toDto(state, cursor))
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GitPersistFailedException(taskId, key.stage(), key.attempt(), "serializing state.json", e);
        }
    }

    /**
     * Renders the round's trace file as the bytes the environment channel carries. The content
     * itself is {@link TraceLineWriter#render(ToolTrace)}'s, the same renderer the host medium
     * writes to a worktree through, so only the encoding is decided here.
     *
     * @param trace the round's tool calls
     * @return the trace file's bytes, one rendered line per call
     */
    static byte[] trace(ToolTrace trace) {
        return TraceLineWriter.render(trace).getBytes(StandardCharsets.UTF_8);
    }
}
