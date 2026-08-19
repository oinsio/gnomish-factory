package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.app.port.git.UnsupportedStateFileVersionException;
import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.domain.engine.CheckResult;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Maps between the domain's {@link TaskState} tree and the {@code state.json}
 * v1 DTO tree ({@link StateJsonDto}) — the mapper counterpart of {@code
 * status.json.AttemptMapper}, kept as a wholly separate contract (design D5).
 * Usage/token mapping delegates to {@link StateUsageMapper}. Every sealed
 * domain type is mapped through an exhaustive switch with no {@code default}
 * arm: a new variant fails to compile here until its mapping is added.
 *
 * <p>Unlike {@code task.json}'s mapper, {@link #fromDto(StateJsonDto)} rebuilds
 * a full domain {@link TaskState}: every field a {@code TaskState} needs is
 * present on the wire. The one caveat is {@link CheckRef#index()} — the wire
 * format carries only a check's derived {@code label}, never its zero-based
 * list position, so {@link #fromDto} reconstructs each {@code CheckRef} with
 * index {@code 0} as a placeholder. No caller reconstructs a {@code CheckRef}'s
 * index from {@code state.json} today (same documented caveat as {@code
 * task.json}'s {@code EscalationReportDto.CannotVerify} mapping).
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 */
public final class StateJsonMapper {

    private StateJsonMapper() {}

    /**
     * Parses raw {@code state.json} text (e.g. a {@code git show} blob) into a
     * {@link StateJsonDto}, refusing an unsupported or missing {@code "version"}
     * before attempting to bind the rest of the document (FR4). Unknown fields
     * elsewhere are tolerated per {@link TaskStateJson#mapper()}.
     *
     * @param json the raw {@code state.json} text; never null
     * @return the parsed and version-gated DTO
     * @throws UnsupportedStateFileVersionException if {@code "version"} is
     *     missing or is not {@code 1}
     */
    public static StateJsonDto readDto(String json) {
        return StateFileVersionGate.readGated(TaskStateJson.mapper(), "state.json", json, 1, StateJsonDto.class);
    }

    /**
     * Builds the {@code state.json} DTO tree from a domain {@link TaskState}, with
     * no egress cursor — the shape a writer with no denial source produces (host
     * mode).
     *
     * @param state the task state to render; never null
     * @return the equivalent {@code state.json} DTO tree
     */
    public static StateJsonDto toDto(TaskState state) {
        return toDto(state, null);
    }

    /**
     * Builds the {@code state.json} DTO tree from a domain {@link TaskState},
     * carrying the environment's denial read position at commit time (FR5 of
     * fix-denial-report-attachment) so a resuming instance can continue the delta
     * rather than replay its source's whole log.
     *
     * <p>The cursor is deliberately not part of {@link TaskState}: it describes the
     * environment that produced the attempt, not the task's position in its
     * pipeline, and only the environment interprets it.
     *
     * @param state the task state to render; never null
     * @param egressCursor the environment's denial cursor, or null when it has none
     * @return the equivalent {@code state.json} DTO tree
     */
    public static StateJsonDto toDto(TaskState state, @Nullable StateEgressCursorDto egressCursor) {
        return new StateJsonDto(
                1,
                toPosition(state.position()),
                state.attemptsUsed(),
                state.attempts().stream().map(StateJsonMapper::toAttempt).toList(),
                StateUsageMapper.toUsage(state.totals()),
                egressCursor);
    }

    /**
     * Rebuilds the domain {@link TaskState} from a parsed {@code state.json}
     * DTO tree.
     *
     * @param dto the DTO tree to map; never null
     * @return the equivalent domain task state
     */
    public static TaskState fromDto(StateJsonDto dto) {
        return new TaskState(
                fromPosition(dto.position()),
                dto.attemptsUsed(),
                dto.attempts().stream().map(StateJsonMapper::fromAttempt).toList(),
                StateUsageMapper.fromUsage(dto.totals()));
    }

    private static StatePositionDto toPosition(Position position) {
        return switch (position) {
            case Position.AtStage atStage -> new StatePositionDto.AtStage("atStage", atStage.name());
            case Position.PipelineEnd ignored -> new StatePositionDto.PipelineEnd("pipelineEnd");
        };
    }

    private static Position fromPosition(StatePositionDto dto) {
        return switch (dto) {
            case StatePositionDto.AtStage atStage -> new Position.AtStage(atStage.stage());
            case StatePositionDto.PipelineEnd ignored -> new Position.PipelineEnd();
        };
    }

    private static StateAttemptDto toAttempt(AttemptRecord record) {
        return new StateAttemptDto(
                record.round(),
                toResult(record.result()),
                record.startedAt().toString(),
                record.checkResults().stream().map(StateJsonMapper::toCheck).toList(),
                StateFindingMapper.toDtos(record.denials()),
                StateUsageMapper.toUsage(record.executorUsage()),
                StateUsageMapper.toJudgeUsage(record.judgeUsage()));
    }

    /**
     * Rebuilds one domain {@link AttemptRecord} from its {@code state.json} DTO — the same
     * mapping {@link #fromDto(StateJsonDto)} applies to every element of {@code attempts},
     * exposed because the usage-history walk reconstructs rounds one at a time as it diffs
     * successive commits, and its port-level rows carry the domain record rather than this
     * package's wire DTO (FR12b, design D12 of split-into-modules).
     *
     * @param dto the per-attempt DTO to map; never null
     * @return the equivalent domain attempt record
     */
    public static AttemptRecord fromAttempt(StateAttemptDto dto) {
        return new AttemptRecord(
                dto.round(),
                fromResult(dto.result()),
                Instant.parse(dto.startedAt()),
                dto.checks().stream().map(StateJsonMapper::fromCheck).toList(),
                StateUsageMapper.fromUsage(dto.executorUsage()),
                StateUsageMapper.fromJudgeUsage(dto.judgeUsage()),
                StateFindingMapper.fromDtos(dto.denials()));
    }

    private static String toResult(AttemptRecord.Result result) {
        return switch (result) {
            case PASSED -> "passed";
            case QUALITY_FAILURE -> "qualityFailure";
            case CANNOT_VERIFY -> "cannotVerify";
            case DECISION_NEEDED -> "decisionNeeded";
        };
    }

    private static AttemptRecord.Result fromResult(String result) {
        return switch (result) {
            case "passed" -> AttemptRecord.Result.PASSED;
            case "qualityFailure" -> AttemptRecord.Result.QUALITY_FAILURE;
            case "cannotVerify" -> AttemptRecord.Result.CANNOT_VERIFY;
            case "decisionNeeded" -> AttemptRecord.Result.DECISION_NEEDED;
            default -> throw new IllegalArgumentException("Unknown AttemptRecord.Result: " + result);
        };
    }

    private static StateCheckDto toCheck(CheckResult check) {
        long durationMillis = check.duration().toMillis();
        return switch (check.verdict()) {
            case Verdict.Pass pass ->
                new StateCheckDto(
                        check.checkRef().label(), "pass", List.of(), durationMillis, null, null, pass.runUrl());
            case Verdict.Fail fail ->
                new StateCheckDto(
                        check.checkRef().label(),
                        "fail",
                        StateFindingMapper.toDtos(fail.findings()),
                        durationMillis,
                        null,
                        null,
                        null);
            case Verdict.CannotVerify cannotVerify ->
                new StateCheckDto(
                        check.checkRef().label(),
                        "cannotVerify",
                        List.of(),
                        durationMillis,
                        cannotVerify.reason(),
                        cannotVerify.details(),
                        null);
        };
    }

    private static CheckResult fromCheck(StateCheckDto dto) {
        // The wire format carries only the check's label, never its zero-based
        // list index; index 0 is a placeholder (see class-level note).
        CheckRef ref = new CheckRef(0, dto.ref());
        Verdict verdict =
                switch (dto.verdict()) {
                    case "pass" -> new Verdict.Pass(dto.runUrl());
                    case "fail" -> new Verdict.Fail(StateFindingMapper.fromDtos(dto.findings()));
                    case "cannotVerify" ->
                        new Verdict.CannotVerify(
                                Objects.requireNonNull(dto.reason(), "reason"),
                                Objects.requireNonNull(dto.details(), "details"));
                    default -> throw new IllegalArgumentException("Unknown Verdict: " + dto.verdict());
                };
        return new CheckResult(ref, verdict, Duration.ofMillis(dto.durationMillis()));
    }
}
