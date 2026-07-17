package com.github.oinsio.gnomish.status.json;

import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.CheckResult;
import com.github.oinsio.gnomish.domain.engine.Verdict;
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
 * <p>Implements FR11, M3 of add-manual-run.
 */
final class AttemptMapper {

    private AttemptMapper() {}

    static AttemptDto toDto(AttemptRecord record) {
        return new AttemptDto(
                record.round(),
                toResult(record.result()),
                record.startedAt().toString(),
                toChecks(record.checkResults()),
                UsageMapper.toUsage(record.executorUsage()),
                UsageMapper.toJudgeUsage(record.judgeUsage()));
    }

    private static String toResult(AttemptRecord.Result result) {
        return switch (result) {
            case PASSED -> "passed";
            case QUALITY_FAILURE -> "qualityFailure";
            case CANNOT_VERIFY -> "cannotVerify";
            case DECISION_NEEDED -> "decisionNeeded";
        };
    }

    private static List<CheckDto> toChecks(List<CheckResult> checks) {
        return checks.stream().map(AttemptMapper::toCheck).toList();
    }

    static CheckDto toCheck(CheckResult check) {
        long durationMillis = check.duration().toMillis();
        return switch (check.verdict()) {
            case Verdict.Pass ignored ->
                new CheckDto(check.checkRef().label(), "pass", List.of(), durationMillis, null, null);
            case Verdict.Fail fail ->
                new CheckDto(check.checkRef().label(), "fail", toFindings(fail), durationMillis, null, null);
            case Verdict.CannotVerify cannotVerify ->
                new CheckDto(
                        check.checkRef().label(),
                        "cannotVerify",
                        List.of(),
                        durationMillis,
                        cannotVerify.reason(),
                        cannotVerify.details());
        };
    }

    private static List<FindingDto> toFindings(Verdict.Fail fail) {
        return fail.findings().stream()
                .map(finding -> new FindingDto(finding.message(), finding.location(), finding.details()))
                .toList();
    }
}
