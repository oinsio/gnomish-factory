package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.domain.engine.DenialIdentity;
import com.github.oinsio.gnomish.domain.engine.Finding;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The one denial shape both task-branch envelopes use, both ways: a finding plus the identity its
 * source assigned it (FR7 of fix-denial-attribution-durability).
 *
 * <p>Beside {@link StateFindingMapper} rather than inside it, because the two map different
 * things: a check's findings have no source and no event of their own, while a denial is an event
 * some source recorded and can therefore be recognized again on a re-read.
 *
 * <p>The identity is optional in both directions. An entry with none reads as "unknown, keep" —
 * documents written before the field existed, and loss markers, which stand for events whose
 * identities are exactly what was lost.
 *
 * <p>Implements FR4 of fix-denial-report-attachment; FR7 of fix-denial-attribution-durability.
 */
final class StateDenialMapper {

    private StateDenialMapper() {}

    static List<StateDenialDto> toDtos(List<Denial> denials) {
        return denials.stream()
                .map(denial -> new StateDenialDto(
                        denial.finding().message(),
                        denial.finding().location(),
                        denial.finding().details(),
                        toIdentity(denial.identity())))
                .toList();
    }

    static List<Denial> fromDtos(List<StateDenialDto> denials) {
        return denials.stream()
                .map(dto -> new Denial(
                        new Finding(dto.message(), dto.location(), dto.details()), fromIdentity(dto.identity())))
                .toList();
    }

    private static @Nullable DenialIdentityDto toIdentity(@Nullable DenialIdentity identity) {
        return identity == null ? null : new DenialIdentityDto(identity.source(), identity.eventAt());
    }

    /**
     * The one wire-to-domain identity conversion of this envelope pair — shared with {@link
     * RecordedDenialIdentities}, which reads the same field out of the same DTOs without needing
     * the findings around it. Two copies of this line are two places a renamed DTO field can be
     * fixed in only one.
     */
    static @Nullable DenialIdentity fromIdentity(@Nullable DenialIdentityDto dto) {
        return dto == null ? null : new DenialIdentity(dto.source(), dto.at());
    }

    /**
     * An absent {@code denials} field reads as an empty list (FR4 of fix-denial-report-attachment,
     * FR2 of fix-denial-attribution-durability): the field is additive under contract v1, so a
     * document written before it existed must keep parsing. Shared by {@link StateAttemptDto} and
     * {@link EscalationReportDto.CannotExecute}, the two envelopes that carry denials.
     *
     * <p>Kept as an explicit static method rather than inline in either compact constructor — PIT's
     * record filter suppresses mutations inside a record's canonical constructor, which would
     * exempt this default from the mutation gate.
     */
    static List<StateDenialDto> absentAsEmpty(@Nullable List<StateDenialDto> denials) {
        return denials == null ? List.of() : List.copyOf(denials);
    }
}
