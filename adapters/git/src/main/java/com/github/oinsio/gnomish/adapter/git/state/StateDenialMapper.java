package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.domain.engine.DenialIdentity;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.sandbox.environment.ContainerIdSyntax;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(StateDenialMapper.class);

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
     *
     * <p>The restored {@code source} is held to {@link ContainerIdSyntax} here (task 5.3, design
     * D11 of type-untrusted-text): it is the same denial-source id {@code DenialCursor} carries,
     * read back off a document another instance wrote, and the gate its producer applied at the
     * daemon is what this reader owes it — the id stays a {@code String} because it is compared,
     * not rendered. An identity whose source fails the gate is dropped, which reads exactly as an
     * entry that carries none: "unknown, keep", so the merge keeps the denial rather than
     * filtering it on an identity the factory cannot vouch for.
     *
     * <p>{@code eventAt} deliberately takes no gate (design D3's branch-document row): it is a
     * source-assigned stamp the factory stores and compares and no sink renders — {@code
     * RecordedDenialMerge} logs counts only — so there is nothing for a gate to protect.
     *
     * <p>The drop leaves a DEBUG trace (`logging.md`, "Best effort must still leave a trace"): the
     * degraded outcome is a denial that can no longer be recognized on a re-read, and the only
     * thing that explains it is the id the document held.
     */
    static @Nullable DenialIdentity fromIdentity(@Nullable DenialIdentityDto dto) {
        if (dto == null) {
            return null;
        }
        Optional<String> source = ContainerIdSyntax.of(dto.source());
        if (source.isEmpty()) {
            log.debug(
                    "recorded denial identity names '{}', which is not a denial source id;"
                            + " the denial is kept with no identity and re-attached on a re-read",
                    LogText.forLog(dto.source()));
            return null;
        }
        return new DenialIdentity(source.get(), dto.at());
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
