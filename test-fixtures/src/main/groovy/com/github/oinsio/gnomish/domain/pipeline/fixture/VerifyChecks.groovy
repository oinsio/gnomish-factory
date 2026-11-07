package com.github.oinsio.gnomish.domain.pipeline.fixture

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration

/**
 * Builds {@link VerifyCheck.Executor} checks in the three legacy manifest forms for specs, so a
 * spec states the check it means — a {@code builtin}, a {@code command}, an {@code external} —
 * rather than the eleven fields of the collapsed record (FR1 of
 * migrate-verify-to-executor-contract). The executor names are the ones the loader's mapper
 * gives each token; an {@code external} check takes its executor name verbatim, the way a spec
 * names the provider it wires.
 */
final class VerifyChecks {

    private VerifyChecks() {}

    /** A {@code builtin} check: the {@code files-exist} built-in, its name as the check id. */
    static VerifyCheck.Executor builtin(String name, Map<String, Object> params = [:]) {
        check(VerifyCheck.Token.BUILTIN, VerifyCheck.Executor.FILES_EXIST, name, params,
                Duration.ZERO, Duration.ZERO, VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.SAME_BOX)
    }

    /** A {@code command} check: the {@code shell} built-in, its command line as the check id. */
    static VerifyCheck.Executor command(String line, VerifyCheck.VerifyIn verifyIn = VerifyCheck.VerifyIn.SAME_BOX) {
        check(VerifyCheck.Token.COMMAND, VerifyCheck.Executor.SHELL, line, [:],
                Duration.ZERO, Duration.ZERO, VerifyCheck.TimeoutClass.QUALITY, [], verifyIn)
    }

    /** An {@code external} check served by {@code executorName}, with settings and pin paths. */
    static VerifyCheck.Executor external(String checkId, String executorName, Map<String, Object> settings,
            Duration interval, Duration timeout, VerifyCheck.TimeoutClass timeoutClass, List<String> pinPaths) {
        check(VerifyCheck.Token.EXTERNAL, executorName, checkId, settings,
                interval, timeout, timeoutClass, pinPaths, VerifyCheck.VerifyIn.SAME_BOX)
    }

    /** An {@code external} check with no settings and no pin paths. */
    static VerifyCheck.Executor external(String checkId, String executorName,
            Duration interval, Duration timeout, VerifyCheck.TimeoutClass timeoutClass) {
        external(checkId, executorName, [:], interval, timeout, timeoutClass, [])
    }

    /** An {@code external} check with pin paths but no settings. */
    static VerifyCheck.Executor external(String checkId, String executorName,
            Duration interval, Duration timeout, VerifyCheck.TimeoutClass timeoutClass, List<String> pinPaths) {
        external(checkId, executorName, [:], interval, timeout, timeoutClass, pinPaths)
    }

    private static VerifyCheck.Executor check(VerifyCheck.Token token, String executorName, String checkId,
            Map<String, Object> settings, Duration interval, Duration timeout, VerifyCheck.TimeoutClass timeoutClass,
            List<String> pinPaths, VerifyCheck.VerifyIn verifyIn) {
        new VerifyCheck.Executor(token, executorName, checkId, settings, interval, timeout, timeoutClass,
                pinPaths, verifyIn, VerifyCheck.Threshold.ERROR, [])
    }
}
