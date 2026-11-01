package com.github.oinsio.gnomish.executorcontract.usagewire;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.github.oinsio.gnomish.executorcontract.ModelUsage;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.ToolUsage;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One participant entry of a recorded round's {@code usage} list (design D14 of
 * define-executor-contract), the same in {@code state.json}, the status report and the usage
 * report: {@code {"role", "executor", "vote"?, "provenance", "wallMillis"?, "byTool", "byModel"}}.
 * {@code vote} is written on a {@code verdict} entry only and {@code wallMillis} only when known;
 * {@code byTool} and {@code byModel} are always written, possibly empty, and an {@code absent} entry
 * carries an empty {@code byModel}. Each model entry carries the four counts and an optional {@code
 * costUsd}, omitted when unpriced, never written as zero. Wire data only: the mapper in {@code
 * :application} builds it from the domain entry and back.
 *
 * <p>Unknown-token arms: a role token outside the contract's set reads as {@code null}, which the
 * mapper refuses — a record is never read with a participant it cannot name; a provenance outside
 * the set reads as {@link RecordedProvenance#ABSENT}.
 *
 * <p>Implements FR15, FR16 of define-executor-contract.
 *
 * @param role the participant's executor role; {@code null} only when read from an unknown token
 * @param executor the executor's name
 * @param vote the 0-based vote index of a {@code verdict} entry; {@code null} otherwise
 * @param provenance whether the executor reported its usage
 * @param wallMillis the invocation's wall time in milliseconds, or {@code null} when unknown
 * @param byTool the per-tool aggregates; possibly empty
 * @param byModel the usage per resolved model id; possibly empty
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParticipantUsageDto(
        @Nullable Role role,
        String executor,
        @Nullable Integer vote,
        RecordedProvenance provenance,
        @Nullable Long wallMillis,
        @JsonSetter(nulls = Nulls.AS_EMPTY) List<ToolUsage> byTool,
        @JsonSetter(nulls = Nulls.AS_EMPTY) Map<String, ModelUsage> byModel) {}
