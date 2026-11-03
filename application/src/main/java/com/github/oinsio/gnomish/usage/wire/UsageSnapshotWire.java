package com.github.oinsio.gnomish.usage.wire;

import com.github.oinsio.gnomish.domain.engine.ExecutorRole;
import com.github.oinsio.gnomish.domain.engine.ModelUsage;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.domain.engine.UsageCost;
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.usagewire.UsageCostDto;
import com.github.oinsio.gnomish.executorcontract.usagewire.UsageSnapshotDto;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The one mapping between the domain's {@link UsageSnapshot} and the usage wire vocabulary's {@link
 * UsageSnapshotDto} (design D10, D15 of define-executor-contract): {@code state.json}'s mapper, the
 * status report's and the usage report's all write {@code totals} through it, and {@code state.json}'s
 * reads it back through it. Roles are written in the contract's role order and models in model-id
 * order, so a document is byte-stable whatever order the snapshot holds them in. It maps; it adds
 * nothing — the snapshot is the {@code UsageFold}'s.
 *
 * <p>Implements FR16, FR17 of define-executor-contract.
 */
public final class UsageSnapshotWire {

    private UsageSnapshotWire() {}

    /**
     * The wire form of a snapshot.
     *
     * @param snapshot the task's {@code TaskState.totals}, or a fold of walked rounds; never null
     * @return the wire snapshot; never null
     */
    public static UsageSnapshotDto toDto(UsageSnapshot snapshot) {
        UsageCost cost = snapshot.cost();
        RoundToken through = snapshot.through();
        return new UsageSnapshotDto(
                toRoles(snapshot.byRole()),
                new UsageCostDto(cost.priced(), toRoles(cost.unpricedTokens()), cost.complete()),
                snapshot.rounds(),
                through == null ? null : through.commit());
    }

    /**
     * The snapshot a recorded {@code totals} names.
     *
     * @param dto the snapshot as read; never null
     * @return the domain snapshot; never null
     * @throws IllegalArgumentException if a role key is outside the contract's set, the token is
     *     not a commit id, or a value is one the domain snapshot refuses
     */
    public static UsageSnapshot fromDto(UsageSnapshotDto dto) {
        UsageCostDto cost = dto.cost();
        String through = dto.through();
        return new UsageSnapshot(
                fromRoles(dto.byRole()),
                new UsageCost(cost.priced(), fromRoles(cost.unpricedTokens()), cost.complete()),
                dto.rounds(),
                through == null ? null : RoundToken.of(through));
    }

    private static Map<String, Map<String, com.github.oinsio.gnomish.executorcontract.ModelUsage>> toRoles(
            Map<ExecutorRole, Map<String, TokenUsage>> byRole) {
        var roles = new LinkedHashMap<String, Map<String, com.github.oinsio.gnomish.executorcontract.ModelUsage>>();
        var ordered = new EnumMap<ExecutorRole, Map<String, TokenUsage>>(ExecutorRole.class);
        ordered.putAll(byRole);
        ordered.forEach((role, models) -> {
            var wire = new TreeMap<String, com.github.oinsio.gnomish.executorcontract.ModelUsage>();
            models.forEach((model, tokens) -> wire.put(model, UsageWireParts.toModel(new ModelUsage(tokens, null))));
            roles.put(ParticipantUsageWire.toRole(role).wire(), wire);
        });
        return roles;
    }

    private static Map<ExecutorRole, Map<String, TokenUsage>> fromRoles(
            Map<String, Map<String, com.github.oinsio.gnomish.executorcontract.ModelUsage>> roles) {
        var byRole = new EnumMap<ExecutorRole, Map<String, TokenUsage>>(ExecutorRole.class);
        roles.forEach((token, models) -> {
            var tokens = new LinkedHashMap<String, TokenUsage>();
            models.forEach((model, usage) -> tokens.put(model, UsageWireParts.tokensOf(usage)));
            byRole.put(ParticipantUsageWire.fromRole(Role.fromWire(token).orElse(null)), tokens);
        });
        return byRole;
    }
}
