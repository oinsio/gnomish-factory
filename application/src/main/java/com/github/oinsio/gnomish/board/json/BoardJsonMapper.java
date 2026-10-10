package com.github.oinsio.gnomish.board.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.board.AwaitingHumanRow;
import com.github.oinsio.gnomish.board.BoardLabels;
import com.github.oinsio.gnomish.board.BoardModel;
import com.github.oinsio.gnomish.board.EligibilityReason;
import com.github.oinsio.gnomish.board.ReadyRow;
import com.github.oinsio.gnomish.board.ReadySummary;
import com.github.oinsio.gnomish.board.WorkingRow;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
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
 * <p>{@code wipLimit} and {@code openFrontCount} are read from the model ({@link
 * BoardModel#wipLimit()}, {@link BoardModel#openFrontCount()}), never taken as a parameter. An
 * earlier version passed the limit in beside the model ("pass config explicitly, don't smuggle it
 * into the model"); that decision is reversed (design D13 of
 * supervise-daemon-loops-and-embed-dashboard). A second renderer, the dashboard's WIP stat, needs
 * the same value, and a limit handed to each renderer separately is two paths for one value with
 * no guarantee either equals the limit the WIP-held rows were judged by. The model now carries
 * exactly that limit, taken from the {@code EligibilityInputs} its rows were resolved against, so
 * the signature leaves no way to print another one.
 *
 * <p>Annotated {@link UntrustedExit} for the same reason the {@code status.json} mappers are
 * (design D2 of type-untrusted-text): {@code board --json} is a parser's input, so a task's
 * tracker-written title goes into it byte for byte; the human board renders the same title
 * through the console exit instead.
 *
 * <p>Implements FR6, NFR-O1, UX4 of add-board-command; FR3 of type-untrusted-text; FR14 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
@UntrustedExit
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
     * @return the pretty-printed JSON document
     */
    public String serialize(BoardModel model) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toDto(model));
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
     * @return the equivalent DTO tree
     */
    public BoardReportDto toDto(BoardModel model) {
        Objects.requireNonNull(model, "model");
        return new BoardReportDto(
                1,
                model.generatedAt().toString(),
                model.truncated(),
                toReadyColumn(model),
                model.workingRows().stream().map(BoardJsonMapper::toWorkingDto).toList(),
                model.awaitingHumanRows().stream()
                        .map(BoardJsonMapper::toAwaitingHumanDto)
                        .toList());
    }

    private static ReadyColumnDto toReadyColumn(BoardModel model) {
        ReadySummary summary = model.summary();
        return new ReadyColumnDto(
                summary.queuedCount(),
                summary.eligibleNowCount(),
                summary.inBackoffCount(),
                summary.finishedCount(),
                summary.wipHeldCount(),
                model.openFrontCount(),
                model.wipLimit(),
                model.readyRows().stream().map(BoardJsonMapper::toReadyRowDto).toList());
    }

    private static ReadyRowDto toReadyRowDto(ReadyRow row) {
        return new ReadyRowDto(
                row.ref().id(), row.title().raw(), row.returned(), toEligibilityDto(row.eligibilityReason()));
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
                row.title().raw(),
                row.holder(),
                claimVersion == null ? null : claimVersion.updatedAt().toString());
    }

    private static AwaitingHumanRowDto toAwaitingHumanDto(AwaitingHumanRow row) {
        return new AwaitingHumanRowDto(row.ref().id(), row.title().raw(), BoardLabels.parkReasonLabel(row.reason()));
    }
}
