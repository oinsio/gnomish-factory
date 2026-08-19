package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.engine.Finding;
import java.util.List;

/**
 * The one finding shape {@code state.json} uses, both ways. Two producers share it:
 * a failed check's {@code findings} and an attempt's {@code denials} — a denial reads
 * exactly like any other finding, which is what lets a reviewer read both without
 * knowing where either came from (FR4, UX1 of fix-denial-report-attachment).
 *
 * <p>Split out of {@link StateJsonMapper} when the second producer arrived, to keep
 * that file within the project's file-size invariant.
 *
 * <p>Implements FR3, FR4 of add-git-workflow; FR4 of fix-denial-report-attachment.
 */
final class StateFindingMapper {

    private StateFindingMapper() {}

    static List<StateFindingDto> toDtos(List<Finding> findings) {
        return findings.stream()
                .map(finding -> new StateFindingDto(finding.message(), finding.location(), finding.details()))
                .toList();
    }

    static List<Finding> fromDtos(List<StateFindingDto> findings) {
        return findings.stream()
                .map(dto -> new Finding(dto.message(), dto.location(), dto.details()))
                .toList();
    }
}
