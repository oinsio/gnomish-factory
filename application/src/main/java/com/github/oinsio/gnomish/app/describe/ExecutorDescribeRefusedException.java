package com.github.oinsio.gnomish.app.describe;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * A declared {@code program} executor could not be described (design D5 of
 * define-executor-contract, as revised 2026-10-31): the preflight's own refusal, raised inside one
 * describe and converted by {@link DescribePreflight} into the located {@link ConfigError} it
 * carries, so it reaches no classifier. Every describe failure is the task's: it parks the task as
 * an invalid law does. Two classes, named in the message: a <em>configuration error</em> (the
 * program exited non-zero, or its answer is missing, oversized, malformed or not inert) and an
 * <em>external failure</em> (the environment refused to start the program, or describe outlived
 * the bound and was killed).
 *
 * <p>What the program itself wrote is quoted only as a capped, neutralized excerpt.
 *
 * <p>Implements FR4, FR13, UX1 of define-executor-contract.
 */
final class ExecutorDescribeRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The most characters of the program's own output a message quotes. */
    static final int DETAIL_CAP_CHARS = 512;

    private final transient ConfigError error;

    private ExecutorDescribeRefusedException(ConfigError error) {
        super(error.message());
        this.error = error;
    }

    /** A deterministic failure: the program answered wrongly or not at all. */
    static ExecutorDescribeRefusedException configuration(
            String executor, String failure, @Nullable UntrustedText detail) {
        return new ExecutorDescribeRefusedException(located(
                executor,
                "executor '" + executor + "' cannot be described (configuration error): " + failure + quoted(detail)
                        + "; fix the program or its declaration, then return the task to work"));
    }

    /** A failure of the environment around the program, possibly transient. */
    static ExecutorDescribeRefusedException external(String executor, String failure, @Nullable UntrustedText detail) {
        return new ExecutorDescribeRefusedException(located(
                executor,
                "executor '" + executor + "' could not be described (external failure): " + failure + quoted(detail)
                        + "; return the task to work once the environment can run it"));
    }

    /** The located error the task parks with. */
    ConfigError error() {
        return error;
    }

    private static ConfigError located(String executor, String message) {
        return new ConfigError("config.yaml", "executors." + executor + ".program", message);
    }

    private static String quoted(@Nullable UntrustedText detail) {
        return detail == null || detail.isBlank() ? "" : " (" + detail.excerpt(DETAIL_CAP_CHARS) + ")";
    }
}
