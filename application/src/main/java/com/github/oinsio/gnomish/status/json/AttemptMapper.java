package com.github.oinsio.gnomish.status.json;

import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.CheckResult;
import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import com.github.oinsio.gnomish.domain.engine.Stop;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import com.github.oinsio.gnomish.usage.wire.ParticipantUsageWire;
import java.util.List;

/**
 * Maps {@link AttemptRecord} and its nested {@link CheckResult}/{@link Verdict}
 * into their wire-format DTOs (task 6.5): {@code result} and {@code verdict}
 * render as lowerCamel discriminator strings; a {@link Verdict}'s payload (its
 * findings, or its {@code reason}/{@code details}) flattens onto the same {@link
 * CheckDto} as sibling fields rather than nesting a verdict object, per the
 * canonical example (see {@link CheckDto}'s type-level note). Exhaustive switches
 * with no {@code default} arm, mirroring the domain's own idiom.
 *
 * <p>Implements FR11, M3 of add-manual-run; FR10, FR12 of make-checkpoint-gate-durable; FR14 of
 * define-executor-contract (the attempt's identity).
 *
 * <p>Annotated {@link UntrustedExit} (design D2 of type-untrusted-text): the {@code --json}
 * document is a parser's input, written byte for byte, so a check's own words go in as they
 * arrived and JSON encoding — not a rendering exit — is what bounds them. Rendering here would
 * corrupt the very output the machine plane exists to keep verbatim.
 */
@UntrustedExit
final class AttemptMapper {

    private AttemptMapper() {}

    static AttemptDto toDto(AttemptRecord record) {
        RoundToken token = record.roundToken();
        return new AttemptDto(
                record.taskId(),
                record.stage(),
                token == null ? null : token.commit(),
                record.round(),
                toResult(record.result()),
                toStop(record.stop()),
                record.startedAt().toString(),
                toChecks(record.checkResults()),
                toFindings(Denial.findings(record.denials())),
                ParticipantUsageWire.toDtos(record.usage()));
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

    /**
     * The round's stop, by the contract's {@code type} tokens (FR10, FR12 of
     * make-checkpoint-gate-durable). The cannot-verify check is named by its label, the same
     * spelling a check's {@code ref} and the {@code lastEscalation} {@code check} use.
     */
    private static StopDto toStop(Stop stop) {
        return switch (stop) {
            case Stop.None ignored -> new StopDto.None("none");
            case Stop.DecisionNeeded decisionNeeded ->
                new StopDto.DecisionNeeded(
                        "decisionNeeded",
                        decisionNeeded.question().raw(),
                        decisionNeeded.options().stream()
                                .map(UntrustedText::raw)
                                .toList());
            case Stop.CannotVerify cannotVerify ->
                new StopDto.CannotVerify(
                        "cannotVerify",
                        cannotVerify.check().label().raw(),
                        cannotVerify.reason().raw(),
                        cannotVerify.details().raw());
            case Stop.BudgetExhausted budgetExhausted ->
                new StopDto.BudgetExhausted(
                        "budgetExhausted", budgetExhausted.bound().raw());
        };
    }

    private static List<CheckDto> toChecks(List<CheckResult> checks) {
        return checks.stream().map(AttemptMapper::toCheck).toList();
    }

    static CheckDto toCheck(CheckResult check) {
        long durationMillis = check.duration().toMillis();
        return switch (check.verdict()) {
            case Verdict.Pass pass ->
                new CheckDto(
                        check.checkRef().label().raw(),
                        "pass",
                        toFindings(pass.findings()),
                        durationMillis,
                        null,
                        null,
                        pass.runUrl());
            case Verdict.Fail fail ->
                new CheckDto(
                        check.checkRef().label().raw(),
                        "fail",
                        toFindings(fail.findings()),
                        durationMillis,
                        null,
                        null,
                        null);
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

    /**
     * The one finding shape the contract uses, shared by a check's findings (a pass's below-threshold ones included), an
     * attempt's denials, and a {@code cannotExecute} escalation's denials — a denial
     * reads exactly like any other finding, so a reviewer needs to know nothing about
     * guard logs to read one (FR4, UX1 of fix-denial-report-attachment; FR2 of
     * fix-denial-attribution-durability). Package-private rather than private so {@link
     * EscalationMapper} renders the escalation's denials through this same one shape.
     */
    static List<FindingDto> toFindings(List<Finding> findings) {
        return findings.stream()
                .map(finding -> new FindingDto(finding.message(), finding.location(), finding.details()))
                .toList();
    }
}
