package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto;
import com.github.oinsio.gnomish.app.port.git.UnsupportedStateFileVersionException;
import com.github.oinsio.gnomish.domain.branch.BranchTipFacts;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.branch.EnvelopeStatus;
import com.github.oinsio.gnomish.domain.branch.RecordedTerminal;
import com.github.oinsio.gnomish.domain.branch.TipRelation;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Turns one {@link BranchTipSource} into the {@link BranchTipFacts} the classifier decides on: this
 * is where the {@code task.json} / {@code state.json} wire format is read, and it is the last place
 * that format is interpreted — everything above works in shapes (design D3). The envelopes are read
 * at the task's registered commit, and only when the tip stands at or above it: a branch whose
 * register is missing or not an ancestor of the tip is classified by that relation alone, so no
 * content the factory never registered is parsed (design D13 of tighten-spec-contract).
 *
 * <p>Reading never throws on content (NFR-R2): a version the build does not support becomes {@link
 * EnvelopeStatus.UnsupportedVersion} carrying both versions, and anything else that fails to parse
 * or bind becomes {@link EnvelopeStatus.Unreadable} carrying the failure's own message. Only
 * environment unavailability reaches the caller, and it does so out of the source before any
 * parsing happens — how a git invocation's outcome is classified as absence versus infrastructure
 * is the tip source's own concern.
 *
 * <p>Implements FR1, FR3, FR13, FR15, NFR-R2 of harden-task-branch-contract; FR11 of
 * make-checkpoint-gate-durable (the recorded position, read from the same commit's {@code
 * state.json}); FR13, NFR-S2 of tighten-spec-contract.
 */
public final class BranchTipFactsReader {

    private static final String TASK_JSON_PATH = EnvelopePaths.TASK_JSON_PATH;
    private static final String STATE_JSON_PATH = EnvelopePaths.STATE_JSON_PATH;

    /**
     * Reads every fact the classification needs from one branch.
     *
     * @param source the medium to read the branch through; never null
     * @return the facts, always complete and never a thrown content failure
     */
    public BranchTipFacts read(BranchTipSource source) {
        TipRelation relation = source.tipRelation();
        if (!readsAtRegister(relation)) {
            return factsFrom(Optional.empty(), Optional.empty(), false, relation);
        }
        Optional<UntrustedText> taskJson = source.readAtRegister(TASK_JSON_PATH);
        Optional<UntrustedText> stateJson = source.readAtRegister(STATE_JSON_PATH);
        return factsFrom(taskJson, stateJson, source.registeredCleanup(), relation);
    }

    /**
     * Whether the tip stands at or above its registered commit — the only relations under which
     * the registered commit's envelope is read at all.
     *
     * @param relation the tip's relation to the register
     * @return {@code true} for {@link TipRelation.AtRegister} and {@link TipRelation.AboveRegister}
     */
    static boolean readsAtRegister(TipRelation relation) {
        return relation instanceof TipRelation.AtRegister || relation instanceof TipRelation.AboveRegister;
    }

    /**
     * Turns envelope texts already read at the registered commit into facts, so a caller that also
     * needs the raw texts (e.g. {@link TipEnvelopeReader}) reads {@code task.json} / {@code
     * state.json} exactly once instead of once here and once more for itself.
     *
     * @param taskJson the registered commit's {@code task.json} text, or empty when absent
     * @param stateJson the registered commit's {@code state.json} text, or empty when absent
     * @param registeredCleanup whether the registered commit is the cleanup commit
     * @param relation the tip's relation to the registered commit
     * @return the facts, always complete and never a thrown content failure
     */
    BranchTipFacts factsFrom(
            Optional<UntrustedText> taskJson,
            Optional<UntrustedText> stateJson,
            boolean registeredCleanup,
            TipRelation relation) {
        Optional<TaskJsonDto> task = Optional.empty();
        Optional<StateJsonDto> state = Optional.empty();
        Optional<Position> position = Optional.empty();

        EnvelopeStatus taskEnvelope;
        try {
            task = taskJson.map(TaskJsonMapper::readDto);
            taskEnvelope = statusOf(taskJson.isPresent());
        } catch (RuntimeException e) {
            taskEnvelope = faultOf(e);
        }

        EnvelopeStatus stateEnvelope;
        try {
            state = stateJson.map(StateJsonMapper::readDto);
            // Mapped inside the guard: a position the domain refuses (a blank stage name) makes the
            // envelope unreadable rather than a throw (NFR-R2). FR11 of make-checkpoint-gate-durable.
            position = state.map(StateJsonDto::position).map(StateJsonMapper::fromPosition);
            stateEnvelope = statusOf(stateJson.isPresent());
        } catch (RuntimeException e) {
            stateEnvelope = faultOf(e);
        }

        return new BranchTipFacts(
                taskEnvelope,
                stateEnvelope,
                task.map(TaskJsonDto::outcome)
                        .map(BranchTipFactsReader::terminalOf)
                        .orElse(RecordedTerminal.NONE),
                position,
                state.map(dto -> isNotEmpty(dto.attempts())).orElse(false),
                task.map(dto -> isNotEmpty(dto.decisions())).orElse(false),
                registeredCleanup,
                relation);
    }

    private static EnvelopeStatus statusOf(boolean present) {
        return present ? new EnvelopeStatus.Parsed() : new EnvelopeStatus.Absent();
    }

    /**
     * Classifies a read failure: the version gate's own refusal keeps both versions so the
     * diagnosis can name them (FR15), and every other failure is an unreadable envelope carrying
     * whatever the parser said went wrong.
     */
    private static EnvelopeStatus faultOf(RuntimeException failure) {
        if (failure instanceof UnsupportedStateFileVersionException version) {
            return new EnvelopeStatus.UnsupportedVersion(version.foundVersion(), version.supportedVersion());
        }
        return new EnvelopeStatus.Unreadable(Objects.requireNonNullElse(failure.getMessage(), failure.toString()));
    }

    /**
     * The recorded outcome's kind. A {@code task.json} with no outcome never reaches here — the
     * absent field is an empty {@link Optional} at the call site, so "no outcome" has exactly one
     * representation rather than two.
     */
    private static RecordedTerminal terminalOf(TaskOutcomeDto outcome) {
        return switch (outcome) {
            case TaskOutcomeDto.Completed ignored -> RecordedTerminal.COMPLETED;
            case TaskOutcomeDto.Paused ignored -> RecordedTerminal.PARKED;
            case TaskOutcomeDto.Escalated ignored -> RecordedTerminal.PARKED;
            case TaskOutcomeDto.Aborted ignored -> RecordedTerminal.PARKED;
        };
    }

    /** A wire list is absent as {@code null} when the document never carried the field at all. */
    private static boolean isNotEmpty(@Nullable List<?> values) {
        return values != null && !values.isEmpty();
    }
}
