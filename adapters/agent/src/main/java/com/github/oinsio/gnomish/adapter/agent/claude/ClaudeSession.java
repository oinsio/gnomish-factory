package com.github.oinsio.gnomish.adapter.agent.claude;

import com.github.oinsio.gnomish.adapter.agent.runner.ContinuationKey;
import com.github.oinsio.gnomish.adapter.agent.runner.ParsedEvents;
import com.github.oinsio.gnomish.executorcontract.Continuation;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The Claude CLI's session, as decision continuation uses it (FR9, NFR-R2, design D4 of
 * extract-agent-round-runner): the {@code init} event's session id is the round's continuation key; a
 * continued start renders {@code --resume <session id>}; and a resume the CLI refused is told apart
 * from every other failure.
 *
 * <p>The refusal, as the CLI's 2.1.325 bundle states it (read from the binary's strings, not measured
 * live): an explicit {@code --resume <id>} whose session the store does not hold prints {@code No
 * conversation found with session ID: <id>} at error level and exits 1, and a session whose
 * transcript cannot be loaded prints {@code Failed to resume session}; both happen before the session
 * starts, so no {@code init} event precedes them. A refusal is therefore a non-zero exit, no {@code
 * init} event, and one of the two sentences in the stderr tail.
 *
 * <p>An {@link UntrustedParser}: the session id becomes a key only through {@link #SESSION_ID}, a
 * syntax that admits the CLI's UUIDs and refuses anything that could read as a flag or carry a space,
 * since the key goes back to the CLI as the argument of {@code --resume}; the reverse direction is held
 * to the same syntax, so a continuation whose key the dialect did not mint is not resumed.
 *
 * <p>Implements FR9, NFR-R2 of extract-agent-round-runner.
 */
@UntrustedParser
final class ClaudeSession {

    /** What a session id may look like: no leading dash, no space, no shell or flag syntax. */
    static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /** The CLI's flag that resumes a session by its id. */
    static final String RESUME_FLAG = "--resume";

    /** The CLI's words for a resume it refused, as its 2.1.325 bundle prints them. */
    static final List<String> REFUSALS = List.of("No conversation found with session ID:", "Failed to resume session");

    private ClaudeSession() {}

    /**
     * The round's continuation key: the session id of its {@code init} event, when it has one the
     * syntax admits.
     *
     * @param events every event the round parsed
     * @return the key, or empty
     */
    static Optional<ContinuationKey> key(ParsedEvents<AgentEvent> events) {
        AgentEvent.@Nullable InitEvent init = events.last(AgentEvent.InitEvent.class);
        if (init == null) {
            return Optional.empty();
        }
        String id = init.sessionId().forParsing();
        return SESSION_ID.matcher(id).matches() ? Optional.of(new ContinuationKey(id)) : Optional.empty();
    }

    /**
     * The argv words that resume {@code continuation}'s session: {@code --resume <id>}, or none for a
     * key the syntax refuses.
     *
     * @param continuation the request's continuation, if any
     * @return the words to append; never null
     */
    static List<String> resume(@Nullable Continuation continuation) {
        if (continuation == null || !SESSION_ID.matcher(continuation.key()).matches()) {
            return List.of();
        }
        return List.of(RESUME_FLAG, continuation.key());
    }

    /**
     * Whether the CLI refused to resume the session it was asked to.
     *
     * @param exitCode the CLI's exit code
     * @param events every event the round parsed
     * @param stderrTail the tail of the CLI's stderr
     * @return true for a non-zero exit before any {@code init} event with the CLI's refusal on stderr
     */
    static boolean refused(int exitCode, ParsedEvents<AgentEvent> events, UntrustedText stderrTail) {
        return exitCode != 0
                && events.last(AgentEvent.InitEvent.class) == null
                && REFUSALS.stream().anyMatch(stderrTail::contains);
    }
}
