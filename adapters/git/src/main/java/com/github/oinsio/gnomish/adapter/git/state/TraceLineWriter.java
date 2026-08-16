package com.github.oinsio.gnomish.adapter.git.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.ToolCall;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a {@link ToolTrace} as {@code attempts/<stage>/<round>/trace.jsonl}
 * under a {@code .gnomish-task/} root: one compact JSON object per line, one
 * line per {@link ToolCall}, in trace order (design D3). Only the line
 * writer itself lives here — actual git commit staging and worktree
 * materialization are the git {@code AttemptPersistence} adapter's job
 * (future work), which this writer is a building block for.
 *
 * <p>Reuses {@link TaskStateJson#mapper()} for the JSON binding: {@code
 * trace.jsonl} follows the same JSON conventions as {@code task.json}/{@code
 * state.json} (camelCase, no unknown-field rejection needed here since this
 * writer never reads the file back) with no separate mapper factory
 * warranted.
 *
 * <p>Overwrite semantics: each call writes a fresh file at the resolved
 * path, since every stage attempt gets its own {@code <round>} directory —
 * this is a new file per round, never appended to across rounds. An empty
 * {@code calls} list still produces a present, zero-line file: parent
 * directories are created and the file is written with empty content,
 * mirroring "an attempt need not have invoked any tool" ({@link
 * ToolTrace#calls()} javadoc).
 *
 * <p>Implements FR3 of add-git-workflow.
 */
public final class TraceLineWriter {

    private static final ObjectMapper MAPPER = TaskStateJson.mapper();

    private TraceLineWriter() {}

    /**
     * Resolves the relative path a trace belongs under, per FR3/design D3:
     * {@code attempts/<stage>/<round>/trace.jsonl}. Stage names are trusted
     * as already-validated safe identifiers (pipeline config loading); no
     * sanitization is performed here.
     *
     * @param key the attempt's correlation key; never null
     * @return the path relative to the {@code .gnomish-task/} root
     */
    public static Path relativePath(AttemptKey key) {
        return Path.of("attempts", key.stage(), String.valueOf(key.attempt()), "trace.jsonl");
    }

    /**
     * Renders one {@code trace.jsonl} line for a single {@link ToolCall}:
     * a compact (non-pretty-printed) JSON object, without a trailing
     * newline — this is a line-oriented log format, one JSON object per
     * line.
     *
     * @param call the tool call to render; never null
     * @return the compact JSON line, no trailing newline
     */
    public static String renderLine(ToolCall call) {
        try {
            return MAPPER.writeValueAsString(TraceLineMapper.toDto(call));
        } catch (IOException e) {
            // Writing a record of primitives/Strings to JSON cannot fail in
            // practice; wrap rather than force a checked throws here.
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes {@code trace} under {@code gnomishTaskRoot} at its resolved
     * relative path ({@link #relativePath(AttemptKey)}), creating parent
     * directories as needed and overwriting any existing file at that path.
     *
     * @param gnomishTaskRoot the {@code .gnomish-task/} directory; never null
     * @param trace the trace to write; never null
     * @throws IOException if the file or its parent directories cannot be
     *     written (an I/O fault, never a validation error)
     */
    public static void write(Path gnomishTaskRoot, ToolTrace trace) throws IOException {
        Path target = gnomishTaskRoot.resolve(relativePath(trace.key()));
        Files.createDirectories(target.getParent());
        StringBuilder content = new StringBuilder();
        for (ToolCall call : trace.calls()) {
            content.append(renderLine(call)).append('\n');
        }
        Files.writeString(target, content.toString());
    }
}
