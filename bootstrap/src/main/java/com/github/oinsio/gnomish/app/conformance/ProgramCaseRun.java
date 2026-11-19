package com.github.oinsio.gnomish.app.conformance;

import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.Workspace;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One case's run of a {@code program} candidate (FR12 of define-executor-contract; design D11 of
 * migrate-verify-to-executor-contract as amended 2026-11-14): {@code start}'s side, made when the
 * case opens, and the poll's, made on the first poll over the case's poll copy ({@link PollCopy}) and
 * the same one after it. Each side is a {@code ProgramExecutor} over an observed environment of its
 * own; the two share the case's table of pending runs, which {@link ProgramCandidate} hands both. The
 * case's breaches are both observations' and closing the run disposes both. The kit drives a case on
 * one thread, so the poll side needs no guard.
 *
 * <p>Implements FR12 of define-executor-contract; FR11, FR12 of migrate-verify-to-executor-contract.
 */
@NullMarked
final class ProgramCaseRun implements KitSubject.CaseRun {

    /**
     * One side of the run.
     *
     * @param executor the side's executor; never null
     * @param workspace the working copy its requests name; never null
     * @param observation the observed environment it runs in; never null
     */
    record Side(Executor executor, Workspace workspace, WireObservation observation) {}

    private final Side start;
    private final Function<Role, Side> pollSide;
    private @Nullable Side poll;

    /**
     * @param start {@code start}'s side; never null
     * @param pollSide makes the poll's side for the role {@code start} ran in, called once; never null
     */
    ProgramCaseRun(Side start, Function<Role, Side> pollSide) {
        this.start = start;
        this.pollSide = pollSide;
    }

    @Override
    public Executor executor() {
        return start.executor();
    }

    @Override
    public Workspace workspace() {
        return start.workspace();
    }

    @Override
    public KitSubject.PollBinding poll(Role role) {
        Side made = poll;
        if (made == null) {
            made = pollSide.apply(role);
            poll = made;
        }
        return new KitSubject.PollBinding(made.executor(), made.workspace());
    }

    @Override
    public List<WireBreach> violations() {
        Side polled = poll;
        if (polled == null) {
            return start.observation().breaches();
        }
        return Stream.concat(start.observation().breaches().stream(), polled.observation().breaches().stream())
                .toList();
    }

    @Override
    public void close() {
        start.observation().dispose();
        Side polled = poll;
        if (polled != null) {
            polled.observation().dispose();
        }
    }
}
