package com.github.oinsio.gnomish.usage.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.app.port.git.UsageRow;
import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.CheckResult;
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.usage.wire.ParticipantUsageWire;
import com.github.oinsio.gnomish.usage.wire.UsageSnapshotWire;
import java.util.List;

/**
 * Maps a {@code gnomish usage} reconstruction ({@link UsageRow} list plus its {@link UsageSnapshot})
 * into its {@code "version": 1} JSON mini-contract DTO tree and serializes it — the {@code --json}
 * counterpart of {@code UsageTextRenderer} (task 5.6), built straight from the same {@link
 * UsageRow}s so no second git-history walk is needed. Every field is carried at full granularity
 * (every participant entry of every round, through the one usage wire vocabulary's mapper,
 * {@link ParticipantUsageWire}) — never summed, unlike the text rendering.
 *
 * <p>Source types are the domain's own ({@link AttemptRecord}, {@link CheckResult}, …), not {@code state.json}'s DTOs: this mapper renders a contract of its own and
 * must not be bound to the git adapter's wire format (FR12b, design D12 of split-into-modules).
 * The flattening rules below — lowerCamel discriminators, a verdict's payload flattened onto the
 * same {@link CheckDto} as sibling fields, {@code Duration.toMillis()} for durations — mirror
 * {@code status.json}'s {@code AttemptMapper} exactly, which is what {@code
 * state.json}'s own mapper mirrors in the other direction (design D5), so the rendered document is
 * byte-identical to what the DTO-sourced mapping produced.
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow; FR16 of define-executor-contract.
 *
 * <p>Annotated {@link UntrustedExit} (design D2 of type-untrusted-text): the {@code --json}
 * document is a parser's input, written byte for byte, so a check's own words go in as they
 * arrived and JSON encoding — not a rendering exit — is what bounds them. Rendering here would
 * corrupt the very output the machine plane exists to keep verbatim.
 */
@UntrustedExit
public final class UsageReportJsonMapper {

    private final ObjectMapper mapper;

    /** Builds a mapper backed by a fresh {@link UsageJson#mapper()} instance. */
    public UsageReportJsonMapper() {
        this.mapper = UsageJson.mapper();
    }

    /**
     * Serializes {@code taskId}'s reconstructed usage as pretty-printed JSON matching the {@code
     * usage --json} v1 mini-contract.
     *
     * @param taskId the task id the rows/totals were reconstructed for
     * @param rows every detected round, oldest to newest; possibly empty
     * @param totals the usage snapshot of {@code rows}
     * @return the pretty-printed JSON document
     */
    public String serialize(String taskId, List<UsageRow> rows, UsageSnapshot totals) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toDto(taskId, rows, totals));
        } catch (JsonProcessingException e) {
            // The DTO tree is plain data with no cyclic references or unsupported types, so this
            // is unreachable in practice; wrap rather than declare a checked exception.
            throw new IllegalStateException("failed to serialize usage report", e);
        }
    }

    private UsageReportDto toDto(String taskId, List<UsageRow> rows, UsageSnapshot totals) {
        return new UsageReportDto(1, taskId, rows.stream().map(this::toRow).toList(), UsageSnapshotWire.toDto(totals));
    }

    private UsageRowDto toRow(UsageRow row) {
        AttemptRecord attempt = row.attempt();
        return new UsageRowDto(
                attempt.stage(),
                attempt.round(),
                toResult(attempt.result()),
                attempt.startedAt().toString(),
                attempt.checkResults().stream()
                        .map(UsageReportJsonMapper::toCheck)
                        .toList(),
                ParticipantUsageWire.toDtos(attempt.usage()));
    }

    private static String toResult(AttemptRecord.Result result) {
        return switch (result) {
            case PASSED -> "passed";
            case QUALITY_FAILURE -> "qualityFailure";
            case CANNOT_VERIFY -> "cannotVerify";
            case DECISION_NEEDED -> "decisionNeeded";
            case BUDGET_EXHAUSTED -> "budgetExhausted";
        };
    }

    private static CheckDto toCheck(CheckResult check) {
        long durationMillis = check.duration().toMillis();
        return switch (check.verdict()) {
            case Verdict.Pass pass ->
                new CheckDto(
                        check.checkRef().label().raw(), "pass", List.of(), durationMillis, null, null, pass.runUrl());
            case Verdict.Fail fail ->
                new CheckDto(
                        check.checkRef().label().raw(), "fail", toFindings(fail), durationMillis, null, null, null);
            case Verdict.CannotVerify cannotVerify ->
                new CheckDto(
                        check.checkRef().label().raw(),
                        "cannotVerify",
                        List.of(),
                        durationMillis,
                        cannotVerify.reason().raw(),
                        cannotVerify.details().raw(),
                        null);
        };
    }

    private static List<FindingDto> toFindings(Verdict.Fail fail) {
        return fail.findings().stream()
                .map(finding -> new FindingDto(finding.message(), finding.location(), finding.details()))
                .toList();
    }
}
