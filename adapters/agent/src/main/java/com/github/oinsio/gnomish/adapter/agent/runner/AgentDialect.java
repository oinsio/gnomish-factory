package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.Usage;
import java.time.Instant;

/**
 * What one agent CLI vendor contributes to a round, and nothing else (FR2, G1 of
 * extract-agent-round-runner): the describe fields, the argv a request renders to, the parse of one
 * output line into the dialect's own events and the vendor-neutral progress they imply, the round's
 * usage, the form its answer takes and the final answer, and the mapping of the exit to a status. The agent round runner depends on nothing else from the vendor (design D1): launch, drains, bounds, the
 * result-file read, denials and the working-copy check are the runner's, the same for every dialect.
 *
 * <p>{@code E} is the dialect's own event type. The runner never looks inside one: it collects the
 * events {@link #parse} returns, stamps each with its arrival instant, and hands them back to the
 * dialect as {@link ParsedEvents}. So a vendor's wire model never leaves its dialect package.
 *
 * <p>Threads: {@link #parse} runs on the round's stdout drain thread, once per line in arrival order,
 * while the process runs; every other method runs on the round's thread after the drain has been
 * joined. An implementation holds no per-round state — the events it parsed are its memory.
 *
 * <p>A dialect continues no vendor session (FR9, NFR-R2, design D4 as amended 2026-11-21): it
 * renders no session flag and declares no {@code resumable}, since the CLI's session lives on the
 * worker and is no record.
 *
 * <p>Implements FR2, FR6, FR8, FR9, FR19, NFR-R2 of extract-agent-round-runner.
 *
 * @param <E> the dialect's own event type
 */
public interface AgentDialect<E> {

    /**
     * The executor's describe as this dialect states it: roles, capabilities, settings schema,
     * credential names, egress hosts, configuration-directory variable, the flags that disable
     * repo-local policy, and the enforcement level.
     *
     * @return the describe; never null
     */
    Describe describe();

    /**
     * The command line {@code request} runs as: the compiled policy, model, bounds as hints, and the
     * answer schema of a role whose answer is schema-enforced — never a session flag, a
     * continuation on the request included.
     *
     * @param request the run, in exactly one role
     * @return the argv, binary first; never null
     */
    RenderedArgv render(ExecutorRequest request);

    /**
     * Parses one line of the CLI's stdout. A line the dialect does not recognize yields {@link
     * ParsedLine#nothing()}, never a throw: the stream is the agent's text, and a line it mangled
     * is not the runner's failure.
     *
     * @param line one line of stdout, without its terminator, possibly capped by the runner
     * @param earlier the events this round parsed before {@code line}, in arrival order
     * @return the dialect's events from this line and the progress they imply; never null
     */
    ParsedLine<E> parse(String line, ParsedEvents<E> earlier);

    /**
     * The round's usage, derived from its events: best effort, so a stream that reported nothing
     * interpretable yields usage with no model entries rather than a failure.
     *
     * @param events every event the round parsed
     * @param roundEnd the instant an unfinished tool call's duration is measured to
     * @return the usage; never null
     */
    Usage usage(ParsedEvents<E> events, Instant roundEnd);

    /**
     * How the agent hands over its answer in {@code role} (FR19 of extract-agent-round-runner, design
     * D15 part 1): schema-enforced — the CLI is handed the reply's schema and {@link #answer} reads
     * the validated reply — or a file the agent writes itself, which the runner reads and never asks
     * this dialect about.
     *
     * @param role the request's role
     * @return the declared form; never null
     */
    AnswerForm answerForm(Role role);

    /**
     * The contract answer a schema-enforced round's final reply states (design D15 part 2), judged
     * against the schema its argv carried for {@code request}; called only for a role whose {@link
     * #answerForm} is {@link AnswerForm.SchemaEnforced}. Writes nothing: the runner writes the answer
     * to the round's answer path.
     *
     * @param request the run the round served, the one {@link #render} rendered
     * @param events every event the round parsed
     * @return the reply's answer, no answer, or a malformed reply; never null
     */
    DialectAnswer answer(ExecutorRequest request, ParsedEvents<E> events);

    /**
     * How the round ended, from the process's exit code and the events: completed, stopped by a
     * turn or budget bound, or broken.
     *
     * @param exitCode the CLI's exit code
     * @param events every event the round parsed
     * @return the status; never null
     */
    RoundStatus status(int exitCode, ParsedEvents<E> events);
}
