package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.app.describe.ProgramChannel;
import com.github.oinsio.gnomish.app.port.check.PendingAdoption;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.executorcontract.Capability;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Optional;
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
 * returned {@code pending} to any binding of the program in this run, for the same subject: the run's
 * table is shared ({@link PendingScope}, design D14 of migrate-verify-to-executor-contract as amended
 * 2026-11-13), so this instance builds none; it spawns nothing for a program whose describe declares
 * no {@code poll}, nor for a key the run neither received for the subject nor {@linkplain #adopt
 * adopted} (FR23).
 *
 * <p>Implements FR5, FR6, FR10, FR11, FR23, NFR-R2, NFR-S1, NFR-S4 of define-executor-contract; FR3,
 * FR11, NFR-R1 of migrate-verify-to-executor-contract.
 */
public final class ProgramExecutor implements Executor, PendingAdoption {

    private final ExecutorDeclaration declaration;
    private final Describe describe;
    private final TaskExecutionEnvironment environment;
    private final ProgramRoundEquipment equipment;
    private final ProgramSpawn spawn;
    private final PendingScope scope;

    /**
     * @param declaration the law's declaration of the executor; never null
     * @param describe the executor's describe answer, as the preflight obtained it; never null
     * @param credentials the credential names the law grants it, which the preflight agreed; never null
     * @param environment where its runs execute; materialized; never null
     * @param equipment the run's bounds, time, credential grant and stderr parent; never null
     * @param scope the run's table of pending runs of the program, the subject this binding serves and
     *     the {@code HEAD} an adoption expects; never null
     */
    public ProgramExecutor(
            ExecutorDeclaration declaration,
            Describe describe,
            List<String> credentials,
            TaskExecutionEnvironment environment,
            ProgramRoundEquipment equipment,
            PendingScope scope) {
        this.declaration = declaration;
        this.scope = scope;
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
                new WorkingCopyRefusal(ProgramFailures.Subject.PROGRAM, declaration.name(), request).before(unreadable);
            case WorkingCopyView.Taken before ->
                settled(request, null, new PendingRuns.Entry(before), ProgramChannel.START);
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>The poll that ends a {@code verdict} or {@code decision} run judges the working copy against
     * the view taken before its {@code start} (design D6).
     *
     * <p>The poll claims its run before it spawns ({@link PendingRuns#claim}): a second poll of the
     * same key while this one runs is refused as an unknown key, and so is a key a {@code pending}
     * answer superseded, or one whose poll threw at a factory boundary. A poll that ends the run — any
     * answer but {@code pending}, a {@code failed/infrastructure} included — leaves its key naming the
     * ended run, so a re-poll of it spawns the program again and is judged as the ending poll was
     * (design D11 of migrate-verify-to-executor-contract: the program answers the ended run again).
     */
    @Override
    public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        if (!describe.capabilities().contains(Capability.POLL)) {
            return refused("its describe declares no poll capability, so it cannot be polled");
        }
        PendingRuns.Entry entry = scope.runs().claim(scope.subject(), key);
        if (entry == null) {
            return refused(
                    "the pending key '" + UntrustedText.subprocess(key.value()).excerpt(256)
                            + "' is unknown: no start or poll of this program in this run returned it for this check,"
                            + " and the run adopted no such recorded key");
        }
        ExecutorResult refusal = entry.refusal();
        return refusal != null ? refusal : settled(request, key, entry, ProgramChannel.POLL);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A {@code verdict} or {@code decision} run is adopted over git's view of this environment's
     * working copy, taken now, which the poll that ends the run is judged against; a view whose {@code
     * HEAD} is not the commit the factory put the working copy on — the scope's expected {@code HEAD},
     * else the round's last registered commit — or that holds anything but the factory's own {@code
     * state.json} lag, or none taken, refuses the key's poll ({@link RecordedKeyAdoption}). A program
     * whose describe declares no {@code poll} adopts nothing: its poll refuses naming that.
     */
    @Override
    public void adopt(ExecutorRequest request, PendingKey key) {
        if (!describe.capabilities().contains(Capability.POLL)) {
            return;
        }
        PendingRuns.Entry entry = new PendingRuns.Entry(null);
        if (WorkingCopyCheck.applies(request.role())) {
            WorkingCopyCheck check = check();
            Optional<String> expected = Optional.ofNullable(scope.expectedHead())
                    .or(() -> equipment.registered().lastRegistered());
            entry = RecordedKeyAdoption.entry(
                    declaration.name(),
                    key,
                    check.view(),
                    expected,
                    equipment.registered().registeredChain(),
                    check::stateBlobAt);
        }
        scope.runs().adopted(scope.subject(), key, entry);
    }

    private ExecutorResult settled(
            ExecutorRequest request, @Nullable PendingKey polled, PendingRuns.Entry entry, String operation) {
        ExecutorResult result = spawn.spawn(operation, request, polled);
        scope.runs().settled(scope.subject(), polled, entry, result);
        WorkingCopyView.Taken before = entry.before();
        if (before == null || result.pendingKey() != null) {
            return result;
        }
        return check().judge(ProgramFailures.Subject.PROGRAM, declaration.name(), request, before, result);
    }

    private WorkingCopyCheck check() {
        return new WorkingCopyCheck(
                environment, equipment.bounds().startup(), equipment.time().clock(), equipment.registered());
    }

    private ExecutorResult refused(String cause) {
        return ProgramFailures.infrastructure(
                ProgramFailures.Subject.PROGRAM, declaration.name(), cause, UntrustedText.factory(""));
    }
}
