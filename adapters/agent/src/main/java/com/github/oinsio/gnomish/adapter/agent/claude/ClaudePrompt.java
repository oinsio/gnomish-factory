package com.github.oinsio.gnomish.adapter.agent.claude;

import com.github.oinsio.gnomish.executorcontract.Continuation;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.RoleInputs;

/**
 * What the Claude CLI reads on stdin for a request (FR3, FR9 of extract-agent-round-runner): the
 * reply of a continued start, which the resumed session reads as the answer to the question it asked;
 * otherwise the role's input as the request carries it — a {@code transform}'s instructions, a {@code
 * verdict}'s criteria, a {@code decision}'s question with its options one per line.
 *
 * <p>Implements FR3, FR9 of extract-agent-round-runner.
 */
final class ClaudePrompt {

    private ClaudePrompt() {}

    /**
     * The stdin text of {@code request}.
     *
     * @param request the run; never null
     * @return the prompt, possibly empty; never null
     */
    static String of(ExecutorRequest request) {
        Continuation continuation = request.continuation();
        if (continuation != null) {
            return continuation.reply();
        }
        RoleInputs inputs = request.roleInputs();
        if (inputs.instructions() != null) {
            return inputs.instructions();
        }
        if (inputs.criteria() != null) {
            return inputs.criteria();
        }
        String question = inputs.question();
        if (question == null) {
            return "";
        }
        StringBuilder prompt = new StringBuilder(question);
        inputs.options().forEach(option -> prompt.append('\n').append("- ").append(option));
        return prompt.toString();
    }
}
