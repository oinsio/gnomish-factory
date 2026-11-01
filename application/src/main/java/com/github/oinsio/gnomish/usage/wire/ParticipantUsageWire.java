package com.github.oinsio.gnomish.usage.wire;

import com.github.oinsio.gnomish.domain.engine.ExecutorRole;
import com.github.oinsio.gnomish.domain.engine.ModelUsage;
import com.github.oinsio.gnomish.domain.engine.ParticipantUsage;
import com.github.oinsio.gnomish.domain.engine.UsageProvenance;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.usagewire.ParticipantUsageDto;
import com.github.oinsio.gnomish.executorcontract.usagewire.RecordedProvenance;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The one mapping between the domain's {@link ParticipantUsage} and the usage wire vocabulary's
 * {@link ParticipantUsageDto} (design D10, D14 of define-executor-contract): {@code state.json}'s
 * mapper, the status report's and the usage report's all map a round's {@code usage} list through
 * it, so the three documents spell a participant entry identically by construction. It sits in
 * {@code :application} because the vocabulary's module reaches no factory layer and the domain
 * reaches no contract.
 *
 * <p>The domain's role and provenance constants are switched over exhaustively in both directions,
 * so a constant added on either side fails to compile here rather than drifting. The model map is
 * written in model-id order, so a document is byte-stable whatever order the entry holds it in.
 *
 * <p>Implements FR15, FR16 of define-executor-contract.
 */
public final class ParticipantUsageWire {

    private ParticipantUsageWire() {}

    /**
     * The wire entries of a round's participant list, in list order.
     *
     * @param usage the round's participant entries; never null
     * @return the wire entries; never null
     */
    public static List<ParticipantUsageDto> toDtos(List<ParticipantUsage> usage) {
        return usage.stream().map(ParticipantUsageWire::toDto).toList();
    }

    /**
     * The participant list a round's wire entries name, in list order.
     *
     * @param dtos the round's wire entries as read; never null
     * @return the domain entries; never null
     * @throws IllegalArgumentException if an entry names a role outside the contract's set, or
     *     carries a value its domain entry refuses
     */
    public static List<ParticipantUsage> fromDtos(List<ParticipantUsageDto> dtos) {
        return dtos.stream().map(ParticipantUsageWire::fromDto).toList();
    }

    private static ParticipantUsageDto toDto(ParticipantUsage entry) {
        Duration wall = entry.wallTime();
        return new ParticipantUsageDto(
                toRole(entry.role()),
                entry.executor(),
                entry.vote(),
                toProvenance(entry.provenance()),
                wall == null ? null : wall.toMillis(),
                UsageWireParts.toTools(entry.tools()),
                toModels(entry.byModel()));
    }

    private static ParticipantUsage fromDto(ParticipantUsageDto dto) {
        Long wall = dto.wallMillis();
        return new ParticipantUsage(
                fromRole(dto.role()),
                dto.executor(),
                dto.vote(),
                fromProvenance(dto.provenance()),
                wall == null ? null : Duration.ofMillis(wall),
                UsageWireParts.fromTools(dto.byTool()),
                fromModels(dto.byModel()));
    }

    static Role toRole(ExecutorRole role) {
        return switch (role) {
            case TRANSFORM -> Role.TRANSFORM;
            case VERDICT -> Role.VERDICT;
            case DECISION -> Role.DECISION;
            case EFFECT -> Role.EFFECT;
        };
    }

    static ExecutorRole fromRole(@Nullable Role role) {
        if (role == null) {
            throw new IllegalArgumentException("usage entry names a role outside the contract's set");
        }
        return switch (role) {
            case TRANSFORM -> ExecutorRole.TRANSFORM;
            case VERDICT -> ExecutorRole.VERDICT;
            case DECISION -> ExecutorRole.DECISION;
            case EFFECT -> ExecutorRole.EFFECT;
        };
    }

    static RecordedProvenance toProvenance(UsageProvenance provenance) {
        return switch (provenance) {
            case REPORTED -> RecordedProvenance.REPORTED;
            case ABSENT -> RecordedProvenance.ABSENT;
        };
    }

    static UsageProvenance fromProvenance(RecordedProvenance provenance) {
        return switch (provenance) {
            case REPORTED -> UsageProvenance.REPORTED;
            case ABSENT -> UsageProvenance.ABSENT;
        };
    }

    private static Map<String, com.github.oinsio.gnomish.executorcontract.ModelUsage> toModels(
            Map<String, ModelUsage> byModel) {
        var models = new TreeMap<String, com.github.oinsio.gnomish.executorcontract.ModelUsage>();
        byModel.forEach((model, usage) -> models.put(model, UsageWireParts.toModel(usage)));
        return models;
    }

    private static Map<String, ModelUsage> fromModels(
            Map<String, com.github.oinsio.gnomish.executorcontract.ModelUsage> models) {
        var byModel = new LinkedHashMap<String, ModelUsage>();
        models.forEach((model, usage) -> byModel.put(model, UsageWireParts.fromModel(usage)));
        return byModel;
    }
}
