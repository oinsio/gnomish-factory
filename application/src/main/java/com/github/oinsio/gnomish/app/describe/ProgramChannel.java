package com.github.oinsio.gnomish.app.describe;

import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * How the factory addresses a {@code program} executor process (design D3, D5 of
 * define-executor-contract): the declared argv with the operation word appended as its last
 * argument ({@code describe}, {@code start}, {@code poll}), and what one operation is handed as
 * {@code GNOMISH_*} variables — the factory-named files it writes to, paths under the environment's
 * scratch area that did not exist before the run, and for a poll the pending key it continues. The
 * describe preflight and the program executor both address the process through this class, so the
 * two cannot spell the operation, the variables or the paths differently; {@code
 * ProgramChannelOwnerSpec} in {@code :bootstrap} keeps every word and variable name here alone.
 *
 * <p>Implements FR5, FR23 of define-executor-contract.
 */
public final class ProgramChannel {

    /** The operation word of a describe. */
    public static final String DESCRIBE = "describe";

    /** The operation word of a run. */
    public static final String START = "start";

    /**
     * The operation word of a poll: asks a program that declared {@code poll} again for the outcome
     * of a run that returned {@code pending} (FR23).
     */
    public static final String POLL = "poll";

    /** The variable naming the file the program writes its result (or describe answer) to. */
    public static final String RESULT_FILE_VARIABLE = "GNOMISH_RESULT_FILE";

    /** The variable naming the file the program may write its answer payload to. */
    public static final String ANSWER_FILE_VARIABLE = "GNOMISH_ANSWER_FILE";

    /** The variable carrying, to a poll, the pending key the run returned (FR23). */
    public static final String PENDING_KEY_VARIABLE = "GNOMISH_PENDING_KEY";

    private ProgramChannel() {}

    /**
     * The argv of one operation.
     *
     * @param declaration the executor declaration; never null
     * @param operation the operation word; never blank
     * @return the declared argv with the operation appended; immutable
     */
    public static List<String> argv(ExecutorDeclaration declaration, String operation) {
        List<String> argv = new ArrayList<>(declaration.program());
        argv.add(operation);
        return List.copyOf(argv);
    }

    /**
     * A fresh factory-named path under the environment's scratch area for one file of one run.
     *
     * @param environment the run's environment; materialized
     * @param name the executor's name; never blank
     * @param kind what the file holds ({@code describe}, {@code result}, {@code answer}); never blank
     * @return a path no earlier run used; never null
     */
    public static String freshPath(TaskExecutionEnvironment environment, String name, String kind) {
        return environment.scratchRoot() + "/" + kind + "-" + name + "-" + UUID.randomUUID() + ".json";
    }
}
