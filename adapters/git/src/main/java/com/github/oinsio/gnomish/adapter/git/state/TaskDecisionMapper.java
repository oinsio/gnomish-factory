package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Instant;
import java.util.List;

/**
 * Maps the domain's {@link Decision} to and from {@code task.json}'s {@code decisions[]} entries
 * ({@link TaskDecisionDto}) for {@link TaskJsonMapper}, the one reader and writer of the document. A
 * decision that answers a decision request carries the question and options it answers (design D4
 * as amended 2026-11-21, D8 row "The question a decision answers" of extract-agent-round-runner);
 * an entry written before they existed reads with none and is written back unchanged.
 *
 * <p>Moved out of {@link TaskJsonMapper} when the entries gained the question, as {@link
 * TaskEscalationMapper} was: the decision entry is its own part of the wire contract with its own
 * round-trip spec ({@code TaskJsonDecisionQuestionSpec}).
 *
 * <p>Annotated {@link UntrustedExit} like {@link TaskJsonMapper}: it writes the question and the
 * options verbatim into the branch document, and re-mints each as branch text on the way back
 * (design D3 of type-untrusted-text).
 *
 * <p>Implements FR3, FR4 of add-git-workflow; FR9, NFR-R2 of extract-agent-round-runner.
 */
@UntrustedExit
final class TaskDecisionMapper {

    private TaskDecisionMapper() {}

    static List<TaskDecisionDto> toDtos(List<Decision> decisions) {
        return decisions.stream().map(TaskDecisionMapper::toDto).toList();
    }

    static List<Decision> fromDtos(List<TaskDecisionDto> decisions) {
        return decisions.stream().map(TaskDecisionMapper::fromDto).toList();
    }

    private static TaskDecisionDto toDto(Decision decision) {
        EscalationReport.DecisionNeeded answers = decision.answers();
        return new TaskDecisionDto(
                decision.body(),
                decision.author(),
                decision.stage(),
                decision.time() == null ? null : decision.time().toString(),
                answers == null ? null : answers.question().raw(),
                answers == null
                        ? null
                        : answers.options().stream().map(UntrustedText::raw).toList());
    }

    /**
     * Rebuilds a decision as recorded: an entry with a question answers the request it names, the
     * question and options re-minted as the branch's text; an entry without one answers none.
     */
    private static Decision fromDto(TaskDecisionDto dto) {
        Instant at = dto.at() == null ? null : Instant.parse(dto.at());
        String question = dto.question();
        if (question == null) {
            return new Decision(dto.body(), dto.stage(), dto.author(), at);
        }
        List<String> options = dto.options() == null ? List.of() : dto.options();
        var answers = new EscalationReport.DecisionNeeded(
                UntrustedText.branchDocument(question),
                options.stream().map(UntrustedText::branchDocument).toList());
        return new Decision(dto.body(), dto.stage(), dto.author(), at, answers);
    }
}
