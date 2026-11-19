package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.agent.program.ProgramCheckBinding;
import com.github.oinsio.gnomish.adapter.agent.program.RoundExecutorFactory;
import com.github.oinsio.gnomish.app.port.check.BoundCheck;
import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorName;
import org.jspecify.annotations.Nullable;

/**
 * One name of the {@link ExecutorRegistry} (design D18 of migrate-verify-to-executor-contract, as
 * amended 2026-11-10): the executor's name, its describe answer and its long-lived collaborators,
 * built at run assembly, with the executor bound at resolution — never a ready {@link Executor}. A
 * stage entry binds through its {@link RoundExecutorFactory}, by the open round's execution
 * environment (a law's program, the {@code command} built-in); a check entry binds through its
 * {@link CheckBinding}, by the check and its working copy (the five verdict built-ins). A law's
 * program binds a check through its {@link ProgramCheckBinding}, in a fresh box per request, and only
 * in a run that has fresh boxes (task 5.3; proposal NG14): run assembly refuses a law whose program
 * check would run elsewhere ({@code ProgramCheckPlacement}), so no law reaches {@link #executor}'s
 * refusal. The entry keeps nothing it bound.
 *
 * <p>Implements FR1, FR3, FR13 of migrate-verify-to-executor-contract; FR9, FR10 of
 * define-executor-contract.
 */
final class RegistryEntry {

    private final ExecutorName name;
    private final Describe describe;
    private final @Nullable RoundExecutorFactory rounds;
    private final @Nullable CheckBinding check;
    private final @Nullable ProgramCheckBinding boxed;

    private RegistryEntry(
            ExecutorName name,
            Describe describe,
            @Nullable RoundExecutorFactory rounds,
            @Nullable CheckBinding check,
            @Nullable ProgramCheckBinding boxed) {
        this.name = name;
        this.describe = describe;
        this.rounds = rounds;
        this.check = check;
        this.boxed = boxed;
    }

    /**
     * The entry of an executor that runs stage rounds.
     *
     * @param describe the executor's describe answer; never null
     * @param rounds what binds its executor to each open round's environment; never null
     * @return the entry, named by {@code rounds}; never null
     */
    static RegistryEntry stage(Describe describe, RoundExecutorFactory rounds) {
        return new RegistryEntry(new ExecutorName(rounds.name()), describe, rounds, null, null);
    }

    /**
     * The entry of a law's {@code program}: its stage rounds, and its check rounds through a fresh box
     * per request in a run that has fresh boxes.
     *
     * @param describe the executor's describe answer; never null
     * @param rounds what binds its executor to each open round's environment; never null
     * @param checks its check binding over the run's fresh boxes, or {@code null} for a run with none
     * @return the entry, named by {@code rounds}; never null
     */
    static RegistryEntry program(Describe describe, RoundExecutorFactory rounds, @Nullable ProgramCheckBinding checks) {
        return new RegistryEntry(new ExecutorName(rounds.name()), describe, rounds, null, checks);
    }

    /**
     * The entry of an executor that runs check rounds.
     *
     * @param name the executor's name; never null
     * @param describe the executor's describe answer; never null
     * @param check what binds its executor to each check and working copy; never null
     * @return the entry; never null
     */
    static RegistryEntry check(ExecutorName name, Describe describe, CheckBinding check) {
        return new RegistryEntry(name, describe, null, check, null);
    }

    /** The executor's name; never null. */
    ExecutorName name() {
        return name;
    }

    /** The executor's describe answer, the same for every binding; never null. */
    Describe describe() {
        return describe;
    }

    /**
     * The stage binding: what the shared round wrapper asks for each round's request and for the
     * executor bound to the round's environment.
     *
     * @return the round-executor factory; never null
     * @throws IllegalStateException when the executor runs no stage round
     */
    RoundExecutorFactory rounds() {
        if (rounds == null) {
            throw new IllegalStateException("executor '" + name.value() + "' runs check rounds only, not a stage's");
        }
        return rounds;
    }

    /**
     * The executor of one check request and what releases its environment when the request ends: a
     * program's, in a fresh box of its own; a built-in's, in no environment of its own.
     *
     * @param judged the check the executor judges; never null
     * @param ref the judged check's identity in the round; never null
     * @param workspace the check's working copy; never null
     * @return the bound check; never null
     * @throws IllegalStateException when the executor runs no check round in this run
     */
    BoundCheck bound(VerifyCheck.Executor judged, CheckRef ref, Workspace workspace) {
        return boxed != null ? boxed.bind(ref, workspace) : BoundCheck.unboxed(executor(judged, ref, workspace));
    }

    /**
     * The check binding of a built-in: a new executor for one check round, in no environment of its own.
     *
     * @param judged the check the executor judges; never null
     * @param ref the judged check's identity in the round; never null
     * @param workspace the check's working copy; never null
     * @return the bound executor; never null
     * @throws IllegalStateException when the executor runs no check round in this run
     */
    Executor executor(VerifyCheck.Executor judged, CheckRef ref, Workspace workspace) {
        if (check == null) {
            throw new IllegalStateException("executor '" + name.value() + "' runs no check round in this run");
        }
        return check.bind(judged, ref, workspace);
    }
}
