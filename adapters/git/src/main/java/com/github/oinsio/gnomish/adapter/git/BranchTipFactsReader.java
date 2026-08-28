package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto;
import com.github.oinsio.gnomish.app.port.git.UnsupportedStateFileVersionException;
import com.github.oinsio.gnomish.domain.branch.BranchTipFacts;
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import com.github.oinsio.gnomish.domain.branch.EnvelopeStatus;
import com.github.oinsio.gnomish.domain.branch.RecordedTerminal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Turns one {@link BranchTipSource} into the {@link BranchTipFacts} the classifier decides on: this
 * is where the {@code task.json} / {@code state.json} wire format is read, and it is the last place
 * that format is interpreted — everything above works in shapes (design D3).
 *
 * <p>Reading never throws on content (NFR-R2): a version the build does not support becomes {@link
 * EnvelopeStatus.UnsupportedVersion} carrying both versions, and anything else that fails to parse
 * or bind becomes {@link EnvelopeStatus.Unreadable} carrying the failure's own message. Only
 * environment unavailability reaches the caller, and it does so out of the source before any
 * parsing happens — how a git invocation's outcome is classified as absence versus infrastructure
 * is the tip source's own concern.
 *
 * <p>Implements FR1, FR3, FR13, FR15, NFR-R2 of harden-task-branch-contract.
 */
public final class BranchTipFactsReader {

    private static final String TASK_JSON_PATH = GnomishTaskPaths.TASK_JSON_PATH;
    private static final String STATE_JSON_PATH = GnomishTaskPaths.STATE_JSON_PATH;

    /**
     * Reads every fact the classification needs from one tip.
     *
     * @param source the medium to read the tip through; never null — the tip's own stamped epoch
     *     is read through it, so no caller has to know where a commit carries one
     * @param liveEpoch the epoch of the claim currently held, or {@code null} for a reader holding
     *     no claim
     * @return the facts, always complete and never a thrown content failure
     */
    public BranchTipFacts read(BranchTipSource source, @Nullable ClaimEpoch liveEpoch) {
        Optional<TaskJsonDto> task = Optional.empty();
        Optional<StateJsonDto> state = Optional.empty();
        Optional<String> taskJson = source.readAtTip(TASK_JSON_PATH);
        Optional<String> stateJson = source.readAtTip(STATE_JSON_PATH);

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
                state.map(dto -> isNotEmpty(dto.attempts())).orElse(false),
                task.map(dto -> isNotEmpty(dto.decisions())).orElse(false),
                source.cleanupCommitInHistory(),
                source.tipEpoch().orElse(null),
                liveEpoch);
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
