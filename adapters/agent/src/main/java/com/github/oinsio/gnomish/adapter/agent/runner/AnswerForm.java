package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.app.describe.ProgramChannel;

/**
 * How a dialect's agent hands over its answer in one role (FR19 of extract-agent-round-runner,
 * design D15 part 1): the one declaration every consumer switches over — the runner (it writes a
 * schema-enforced answer to the round's answer path, or reads the file the agent wrote), the stage
 * half's policy (a pinpoint write allowance on the answer path only for the file form), and the
 * round prompt (the instruction is the form's own words, never the vendor-neutral builder's).
 *
 * <p>Implements FR19 of extract-agent-round-runner.
 */
public sealed interface AnswerForm {

    /**
     * The words that tell the agent of a {@code transform} round how to ask for a human decision in
     * this form; the round prompt carries them as they are.
     *
     * @return the instruction; never blank
     */
    String instruction();

    /**
     * The CLI is handed the answer's schema and returns a validated object as its final reply; the
     * runner writes the contract answer the dialect derived from it to the round's answer path, and
     * the agent is granted no write under the task's envelope directory.
     *
     * @param instruction the dialect's words for asking through the final structured reply; never
     *     blank, naming no answer file path
     */
    record SchemaEnforced(String instruction) implements AnswerForm {

        /** Refuses a blank instruction. */
        public SchemaEnforced {
            if (instruction.isBlank()) {
                throw new IllegalArgumentException("a schema-enforced answer form needs its instruction");
            }
        }
    }

    /**
     * The agent writes the answer file itself, at the path {@code GNOMISH_ANSWER_FILE} names, under a
     * pinpoint write allowance for that one path.
     */
    record AgentFile() implements AnswerForm {

        /** The file form's instruction; the variable is named by its one owner, {@link ProgramChannel}. */
        static final String INSTRUCTION = """
                === Asking a human ===
                If you need a human decision before you can proceed, write a JSON \
                object of the form {"kind": "decision-request", "question": "...", \
                "options": ["...", "..."]} to the path in the $%s \
                environment variable and finish your turn. Leave that file untouched \
                if you do not need to ask anything.
                """.formatted(ProgramChannel.ANSWER_FILE_VARIABLE);

        @Override
        public String instruction() {
            return INSTRUCTION;
        }
    }
}
