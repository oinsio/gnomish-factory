package com.github.oinsio.gnomish.board.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.board.AwaitingHumanRow;
import com.github.oinsio.gnomish.board.BoardModel;
import com.github.oinsio.gnomish.board.EligibilityReason;
import com.github.oinsio.gnomish.board.ReadyRow;
import com.github.oinsio.gnomish.board.ReadySummary;
import com.github.oinsio.gnomish.board.WorkingRow;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Maps a {@link BoardModel} to its JSON-contract DTO tree and serializes it — the
 * sibling of {@code StatusReportJsonMapper} for the board (task 4.2). Every sealed
 * domain type ({@link EligibilityReason}, {@link
 * com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState}'s {@code
 * ParkReason}) is mapped through an exhaustive switch with no {@code default} arm,
 * mirroring the domain's own exhaustive-switch idiom: a new variant fails to
 * compile here until its mapping is added.
 *
 * <p>{@code wipLimit} is not carried on {@code BoardModel} (it is consumed
 * transiently while {@code BoardModel.build} resolves each row's eligibility), so
 * {@link #serialize} and {@link #toDto} take it as an explicit parameter — the same
 * "pass config explicitly, don't smuggle it into the model" pattern {@code
 * EligibilityPolicy} itself follows. {@code openFrontCount} needs no such
 * parameter: it is simply {@code model.workingRows().size() +
 * model.awaitingHumanRows().size()}, the same {@code listOpen} result already on
 * the model.
 *
 * <p>Implements FR6, NFR-O1, UX4 of add-board-command.
 */
public final class BoardJsonMapper {

    private final ObjectMapper mapper;

    /** Builds a mapper backed by a fresh {@link BoardJson#mapper()} instance. */
    public BoardJsonMapper() {
        this.mapper = BoardJson.mapper();
    }

    /**
     * Serializes {@code model} as pretty-printed JSON matching the v1 contract.
     *
     * @param model the board model to serialize; never null
     * @param wipLimit the configured WIP limit the model's WIP-held rows were
     *     resolved against
     * @return the pretty-printed JSON document
     */
    public String serialize(BoardModel model, int wipLimit) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toDto(model, wipLimit));
        } catch (JsonProcessingException e) {
            // The DTO tree is plain data with no cyclic references or unsupported
            // types, so this is unreachable in practice; wrap rather than declare
            // a checked exception on every caller.
            throw new IllegalStateException("failed to serialize BoardModel", e);
        }
    }

    /**
     * Builds the JSON-contract DTO tree from {@code model}.
     *
     * @param model the board model to map; never null
     * @param wipLimit the configured WIP limit the model's WIP-held rows were
     *     resolved against
     * @return the equivalent DTO tree
     */
    public BoardReportDto toDto(BoardModel model, int wipLimit) {
        Objects.requireNonNull(model, "model");
        int openFrontCount =
                model.workingRows().size() + model.awaitingHumanRows().size();
        return new BoardReportDto(
                1,
                model.generatedAt().toString(),
                model.truncated(),
                toReadyColumn(model.summary(), model.readyRows(), openFrontCount, wipLimit),
                model.workingRows().stream().map(BoardJsonMapper::toWorkingDto).toList(),
                model.awaitingHumanRows().stream()
                        .map(BoardJsonMapper::toAwaitingHumanDto)
                        .toList());
    }

    private static ReadyColumnDto toReadyColumn(
            ReadySummary summary, List<ReadyRow> rows, int openFrontCount, int wipLimit) {
        return new ReadyColumnDto(
                summary.queuedCount(),
                summary.eligibleNowCount(),
                summary.inBackoffCount(),
                summary.finishedCount(),
                summary.wipHeldCount(),
                openFrontCount,
                wipLimit,
                rows.stream().map(BoardJsonMapper::toReadyRowDto).toList());
    }

    private static ReadyRowDto toReadyRowDto(ReadyRow row) {
        return new ReadyRowDto(row.ref().id(), row.title(), row.returned(), toEligibilityDto(row.eligibilityReason()));
    }

    private static EligibilityDto toEligibilityDto(@Nullable EligibilityReason reason) {
        return switch (reason) {
            case null -> new EligibilityDto(true, null, null);
            case EligibilityReason.InBackoff inBackoff ->
                new EligibilityDto(false, "inBackoff", inBackoff.deadline().toString());
            case EligibilityReason.Finished ignored -> new EligibilityDto(false, "finished", null);
            case EligibilityReason.WipHeld ignored -> new EligibilityDto(false, "wipHeld", null);
        };
    }

    private static WorkingRowDto toWorkingDto(WorkingRow row) {
        ClaimVersion claimVersion = row.claimVersion();
        return new WorkingRowDto(
                row.ref().id(),
                row.title(),
                row.holder(),
                claimVersion == null ? null : claimVersion.updatedAt().toString());
    }

    private static AwaitingHumanRowDto toAwaitingHumanDto(AwaitingHumanRow row) {
        return new AwaitingHumanRowDto(row.ref().id(), row.title(), parkReasonLabel(row.reason()));
    }

    private static String parkReasonLabel(ParkReason reason) {
        return switch (reason) {
            case ESCALATION -> "escalation";
            case INFRA -> "infra";
            case CHECKPOINT -> "checkpoint";
        };
    }
}
