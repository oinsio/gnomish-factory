package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.app.port.git.BasePin;
import com.github.oinsio.gnomish.app.port.git.BaseRefKind;
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.git.UnsupportedStateFileVersionException;
import com.github.oinsio.gnomish.baseref.BaseRule;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Maps between the domain's task-lifecycle types ({@link TaskContext}, {@link
 * Decision}, {@link TaskOutcome}, {@link EscalationReport}) and the {@code
 * task.json} v1 DTO tree ({@link TaskJsonDto}) — the mapper counterpart of
 * {@code status.json.StatusReportJsonMapper}, kept as a wholly separate contract
 * (design D5). Every sealed domain type is mapped through an exhaustive switch
 * with no {@code default} arm: a new variant fails to compile here until its
 * mapping is added.
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 *
 * <p>An {@link UntrustedExit} (design D2 of type-untrusted-text): this writer carries untrusted
 * text to a machine medium, where the document's own encoding bounds it and a neutralized value
 * would corrupt the record. It is therefore one of the few classes that may read
 * {@code UntrustedText.raw()}; the reader on the other side mints the carrier back.
 */
@UntrustedExit
public final class TaskJsonMapper {

    private TaskJsonMapper() {}

    /**
     * Parses raw {@code task.json} text (e.g. a {@code git show} blob) into a
     * {@link TaskJsonDto}, refusing an unsupported or missing {@code "version"}
     * before attempting to bind the rest of the document (FR4). Unknown fields
     * elsewhere are tolerated per {@link TaskStateJson#mapper()}.
     *
     * <p>A bound document is then held to the one content rule this reader owns: a pinned {@code
     * baseRef} must be a well-formed ref name. The pin is the name a resume narrow-fetches, and it
     * was written by another instance into a branch a human can push to, so it is checked where it
     * enters the process — refused, never escaped — and a malformed one becomes the corrupt branch
     * shape naming this document, before any fetch could carry it to origin (NFR-S3 of
     * add-base-ref-resolution, task 12.2).
     *
     * @param json the {@code task.json} text as it was read off the branch; never null
     * @return the parsed and version-gated DTO
     * @throws UnsupportedStateFileVersionException if {@code "version"} is
     *     missing or is not {@code 1}
     * @throws MalformedStateFileException if the document pins a {@code baseRef} that is not a
     *     well-formed ref name
     */
    public static TaskJsonDto readDto(UntrustedText json) {
        TaskJsonDto dto = StateFileVersionGate.readGated(
                TaskStateJson.mapper(), EnvelopePaths.TASK_FILE, json.raw(), 1, TaskJsonDto.class);
        PinnedRefGate.check(dto.baseRef());
        return dto;
    }

    /**
     * Builds the {@code task.json} DTO tree from the domain pieces a {@code
     * TaskRepository} adapter has in hand — there is no single domain aggregate
     * bundling all of them yet (design D1: {@code TaskRepository} is a separate
     * seam from the engine).
     *
     * @param context the task's identity, description and decisions; never null
     * @param baseCommit the commit the task branch was created from; never null
     * @param createdAt when the task was created; never null
     * @param outcome the terminal outcome, or {@code null} while a visit is in
     *     progress
     * @param lastEscalation the last escalation report, or {@code null} if the
     *     task was never escalated
     * @param trackerWritePending {@code true} to record the durable "tracker-write
     *     pending" marker for a terminal park whose tracker write has not confirmed
     *     (FR10 of add-claim-heartbeat); {@code false} to leave no marker
     * @param basePin the {@code (ref, kind, rule)} metadata half of the durable base pin (FR7 of
     *     add-base-ref-resolution), or {@link BasePin#UNPINNED} for a document with no pin — bundled
     *     into one parameter object to keep this method within the project's 7-parameter limit
     * <p>The document's {@code egressCursor} is not a parameter here (design D8 of
     * fix-denial-attribution-durability): it is environment bookkeeping no caller of
     * this mapper holds, and one more positional argument on an already-six-wide
     * signature is exactly the shape whose cursorless variant erased {@code
     * state.json}'s cursor on every RESUMED commit. A writer that has a position
     * attaches it to the returned document through {@link
     * TaskJsonDto#withEgressCursor}, and one that has none leaves it absent.
     *
     * @return the equivalent {@code task.json} DTO tree, with no egress cursor
     */
    public static TaskJsonDto toDto(
            TaskContext context,
            String baseCommit,
            Instant createdAt,
            @Nullable TaskOutcome outcome,
            @Nullable EscalationReport lastEscalation,
            boolean trackerWritePending,
            BasePin basePin) {
        return new TaskJsonDto(
                1,
                context.taskId(),
                context.title().raw(),
                context.body().raw(),
                createdAt.toString(),
                baseCommit,
                toDecisions(context.decisions()),
                outcome == null ? null : toOutcome(outcome),
                lastEscalation == null ? null : toEscalation(lastEscalation),
                trackerWritePending ? Boolean.TRUE : null,
                null,
                basePin.ref(),
                basePin.rule() == null ? null : basePin.rule().wireValue(),
                basePin.kind() == null ? null : basePin.kind().wireValue());
    }

    /**
     * Rebuilds the port-level pieces bundled in {@link TaskRecord} from a parsed
     * {@code task.json} DTO tree. {@code outcome} maps onto {@link RecordedOutcome}
     * rather than round-tripping into {@link TaskOutcome}: that domain type carries
     * data {@code task.json} alone does not have (its {@code finalState}, sourced
     * from {@code state.json} — task 1.3's contract — and {@code Aborted.failedAt}'s
     * structured {@link com.github.oinsio.gnomish.domain.engine.AttemptKey}, which
     * the wire format renders only as an opaque label). Reconstructing a full domain
     * outcome from {@code task.json} alone belongs to whichever caller later joins
     * it with {@code state.json}. {@code lastEscalation}, by contrast, does round-trip
     * fully into the domain {@link EscalationReport}.
     *
     * @param dto the DTO tree to map; never null
     * @return the equivalent port-level pieces, bundled
     */
    public static TaskRecord fromDto(TaskJsonDto dto) {
        // The branch-document re-mint (design D3): the title and body come back as the branch's
        // text — what matters is that another instance wrote the document they were lifted from.
        TaskContext context = new TaskContext(
                dto.taskId(),
                UntrustedText.branchDocument(dto.title()),
                UntrustedText.branchDocument(dto.body()),
                fromDecisions(dto.decisions()));
        EscalationReport lastEscalation = dto.lastEscalation() == null ? null : fromEscalation(dto.lastEscalation());
        // A legacy baseCommit-only document carries no pin field at all: it reads as unpinned
        // rather than guessing a rule (FR7 of add-base-ref-resolution). The kind is separately
        // optional — a pin written before it existed, or by the manual tier which classifies
        // nothing, carries ref and rule without it (D7, revised 2026-09-10).
        BaseRule baseRule = dto.baseRule() == null ? null : BaseRule.fromWire(dto.baseRule());
        BasePin pin = new BasePin(dto.baseRef(), BaseRefKind.fromWire(dto.baseKind()), baseRule);
        return new TaskRecord(
                context,
                dto.baseCommit(),
                Instant.parse(dto.createdAt()),
                dto.outcome() == null ? null : fromOutcome(dto.outcome()),
                lastEscalation,
                Boolean.TRUE.equals(dto.trackerWritePending()),
                pin);
    }

    private static List<TaskDecisionDto> toDecisions(List<Decision> decisions) {
        return decisions.stream()
                .map(decision -> new TaskDecisionDto(
                        decision.body(),
                        decision.author(),
                        decision.stage(),
                        decision.time() == null ? null : decision.time().toString()))
                .toList();
    }

    private static List<Decision> fromDecisions(List<TaskDecisionDto> decisions) {
        return decisions.stream()
                .map(dto -> new Decision(
                        dto.body(), dto.stage(), dto.author(), dto.at() == null ? null : Instant.parse(dto.at())))
                .toList();
    }

    private static TaskOutcomeDto toOutcome(TaskOutcome outcome) {
        return switch (outcome) {
            case TaskOutcome.Completed ignored -> new TaskOutcomeDto.Completed("completed");
            case TaskOutcome.Paused paused -> new TaskOutcomeDto.Paused("paused", paused.passedStage());
            case TaskOutcome.Escalated escalated ->
                new TaskOutcomeDto.Escalated("escalated", toEscalation(escalated.report()));
            case TaskOutcome.Aborted aborted ->
                new TaskOutcomeDto.Aborted(
                        "aborted",
                        aborted.failedAt().toString(),
                        aborted.cause().raw());
        };
    }

    /**
     * Maps the {@code task.json} outcome DTO onto its port-level counterpart, dropping the wire
     * discriminator the DTO carries for Jackson's benefit — nothing above this adapter reads it
     * (FR12b, design D12 of split-into-modules).
     */
    private static RecordedOutcome fromOutcome(TaskOutcomeDto dto) {
        return switch (dto) {
            case TaskOutcomeDto.Completed ignored -> new RecordedOutcome.Completed();
            case TaskOutcomeDto.Paused paused -> new RecordedOutcome.Paused(paused.passedStage());
            case TaskOutcomeDto.Escalated escalated ->
                new RecordedOutcome.Escalated(fromEscalation(escalated.report()));
            case TaskOutcomeDto.Aborted aborted ->
                // The branch-document re-mint (design D3): the cause comes back as the branch's
                // text, whatever medium first produced the trace it renders.
                new RecordedOutcome.Aborted(aborted.failedAt(), UntrustedText.branchDocument(aborted.cause()));
        };
    }

    private static EscalationReportDto toEscalation(EscalationReport report) {
        return switch (report) {
            case EscalationReport.AttemptsExhausted exhausted ->
                new EscalationReportDto.AttemptsExhausted("attemptsExhausted", exhausted.limit());
            case EscalationReport.DecisionNeeded decisionNeeded ->
                new EscalationReportDto.DecisionNeeded(
                        "decisionNeeded",
                        decisionNeeded.question().raw(),
                        decisionNeeded.options().stream()
                                .map(UntrustedText::raw)
                                .toList());
            case EscalationReport.CannotVerify cannotVerify ->
                new EscalationReportDto.CannotVerify(
                        "cannotVerify",
                        cannotVerify.check().label().raw(),
                        cannotVerify.reason().raw(),
                        cannotVerify.details().raw());
            case EscalationReport.PipelineMismatch pipelineMismatch ->
                new EscalationReportDto.PipelineMismatch(
                        "pipelineMismatch", pipelineMismatch.staleStage().raw());
            case EscalationReport.CannotExecute cannotExecute ->
                new EscalationReportDto.CannotExecute(
                        "cannotExecute",
                        cannotExecute.cause().raw(),
                        StateDenialMapper.toDtos(cannotExecute.denials()));
        };
    }

    private static EscalationReport fromEscalation(EscalationReportDto dto) {
        return switch (dto) {
            case EscalationReportDto.AttemptsExhausted exhausted ->
                new EscalationReport.AttemptsExhausted(exhausted.limit());
            case EscalationReportDto.DecisionNeeded decisionNeeded ->
                // The branch-document re-mint (design D3): the question and its options come
                // back as the branch's text, whatever medium first produced them.
                new EscalationReport.DecisionNeeded(
                        UntrustedText.branchDocument(decisionNeeded.question()),
                        decisionNeeded.options().stream()
                                .map(UntrustedText::branchDocument)
                                .toList());
            case EscalationReportDto.CannotVerify cannotVerify ->
                new EscalationReport.CannotVerify(
                        // The label alone cannot rebuild a full CheckRef (index is not
                        // carried in the wire format); index 0 is a placeholder — no
                        // caller reconstructs a CheckRef's index from task.json today.
                        new CheckRef(0, UntrustedText.branchDocument(cannotVerify.check())),
                        UntrustedText.branchDocument(cannotVerify.reason()),
                        UntrustedText.branchDocument(cannotVerify.details()));
            case EscalationReportDto.PipelineMismatch pipelineMismatch ->
                new EscalationReport.PipelineMismatch(UntrustedText.branchDocument(pipelineMismatch.staleStage()));
            case EscalationReportDto.CannotExecute cannotExecute ->
                new EscalationReport.CannotExecute(
                        UntrustedText.branchDocument(cannotExecute.cause()),
                        StateDenialMapper.fromDtos(cannotExecute.denials()));
        };
    }
}
