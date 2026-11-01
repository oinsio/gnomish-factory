package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.app.describe.ProgramChannel;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.executorcontract.Capability;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.PendingKey;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The external adapter of the executor contract (design D2, D3 of define-executor-contract): one
 * law-declared {@code program}, driven through the {@link Executor} interface in one execution
 * environment. Each {@code start} and {@code poll} is one spawn of the program ({@link ProgramSpawn}):
 * the declared argv with the operation word appended, the request on standard input, the result read
 * from the file the factory named. Every outcome is a result, never an exception: an expired bound, a
 * refused start, a missing or malformed result are each {@code failed/infrastructure} naming what went
 * wrong ({@link ProgramFailures}).
 *
 * <p>{@link #describe()} answers what the describe preflight obtained for the run: describe takes no
 * request, and the preflight already ran it in this environment. {@link #poll} continues a run that
 * returned {@code pending} to this instance ({@link PendingRuns}); it spawns nothing for a program
 * whose describe declares no {@code poll}, nor for a key this instance did not receive (FR23).
 *
 * <p>Implements FR5, FR6, FR10, FR11, FR23, NFR-R2, NFR-S1, NFR-S4 of define-executor-contract.
 */
public final class ProgramExecutor implements Executor {

    private final ExecutorDeclaration declaration;
    private final Describe describe;
    private final TaskExecutionEnvironment environment;
    private final ProgramRoundEquipment equipment;
    private final ProgramSpawn spawn;
    private final PendingRuns pending = new PendingRuns();

    /**
     * @param declaration the law's declaration of the executor; never null
     * @param describe the executor's describe answer, as the preflight obtained it; never null
     * @param credentials the credential names the law grants it, which the preflight agreed; never null
     * @param environment where its runs execute; materialized; never null
     * @param equipment the run's bounds, time, credential grant and stderr parent; never null
     */
    public ProgramExecutor(
            ExecutorDeclaration declaration,
            Describe describe,
            List<String> credentials,
            TaskExecutionEnvironment environment,
            ProgramRoundEquipment equipment) {
        this.declaration = declaration;
        this.describe = describe;
        this.environment = environment;
        this.equipment = equipment;
        this.spawn = new ProgramSpawn(
                declaration,
                describe.capabilities().contains(Capability.STREAMS_PROGRESS),
                credentials,
                environment,
                equipment);
    }

    @Override
    public Describe describe() {
        return describe;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A {@code verdict} or {@code decision} run is checked to leave the working copy as it found
     * it ({@link WorkingCopyCheck}): git's view is taken before the run — a view that cannot be taken
     * refuses the run before it starts — and again when the run ends, and a difference refuses its
     * answer. A run that returns {@code pending} has not ended: its answer is not judged, and the view
     * waits for the poll that ends it.
     */
    @Override
    public ExecutorResult start(ExecutorRequest request) {
        if (!WorkingCopyCheck.applies(request.role())) {
            return settled(request, null, new PendingRuns.Entry(null), ProgramChannel.START);
        }
        return switch (check().view()) {
            case WorkingCopyView.Unreadable unreadable ->
                ProgramFailures.infrastructure(
                        declaration.name(),
                        "a " + request.role().wire() + " run must leave the working copy unchanged (policy "
                                + WorkingCopyCheck.policy(request.policy())
                                + "), and its state could not be taken before the run: " + unreadable.why(),
                        UntrustedText.factory(""));
            case WorkingCopyView.Taken before ->
                settled(request, null, new PendingRuns.Entry(before), ProgramChannel.START);
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>The poll that ends a {@code verdict} or {@code decision} run judges the working copy against
     * the view taken before its {@code start} (design D6).
     */
    @Override
    public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        if (!describe.capabilities().contains(Capability.POLL)) {
            return refused("its describe declares no poll capability, so it cannot be polled");
        }
        PendingRuns.Entry entry = pending.of(key);
        if (entry == null) {
            return refused(
                    "the pending key '" + UntrustedText.subprocess(key.value()).excerpt(256)
                            + "' is unknown: no start of this executor instance returned it");
        }
        return settled(request, key, entry, ProgramChannel.POLL);
    }

    private ExecutorResult settled(
            ExecutorRequest request, @Nullable PendingKey polled, PendingRuns.Entry entry, String operation) {
        ExecutorResult result = spawn.spawn(operation, request, polled);
        pending.settled(polled, entry, result);
        WorkingCopyView.Taken before = entry.before();
        if (before == null || result.pendingKey() != null) {
            return result;
        }
        return check().judge(declaration.name(), request, before, result);
    }

    private WorkingCopyCheck check() {
        return new WorkingCopyCheck(
                environment, equipment.bounds().startup(), equipment.time().clock());
    }

    private ExecutorResult refused(String cause) {
        return ProgramFailures.infrastructure(declaration.name(), cause, UntrustedText.factory(""));
    }
}
