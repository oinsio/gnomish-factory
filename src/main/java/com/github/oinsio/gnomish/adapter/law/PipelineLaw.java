package com.github.oinsio.gnomish.adapter.law;

import java.util.Map;

/**
 * The pipeline <em>law</em> of one invocation, frozen (D14, FR19 of add-sandbox-core):
 * the content of every stage's control (instructions) file and every judge check's
 * acceptance-criteria file, read <em>once at invocation start</em> from the law
 * source — the factory-owned clone of the base branch in git/tracker-driven modes,
 * the workspace snapshot at startup in the git-less in-place mode — and held
 * immutable for the invocation's whole lifetime, including the in-process outcome
 * loop.
 *
 * <p>This is the structural separation D14 requires: the copies of these files in the
 * gnome's writable working copy are <em>project content</em>, editable like any file
 * and law only for later tasks once a human merges them; the running task reads its
 * control files and acceptance criteria only from here, never lazily from the working
 * copy at prompt-build time. A stuck or injected gnome therefore cannot weaken its own
 * acceptance criteria or rewrite its own instructions mid-task (reward hacking).
 *
 * <p>Built by {@link PipelineLawReader#freeze}; the readers ({@code
 * ExecutorPromptBuilder}, {@code JudgePromptBuilder}, {@code JudgeCriteriaPreflight},
 * {@code StageBriefing}, {@code InteractiveJudgeVoter}) look content up by the same
 * {@code .gnomish/}-relative ref the {@link
 * com.github.oinsio.gnomish.domain.pipeline.StageDefinition} carries.
 *
 * <p>Implements FR19, NFR-S2, D14 of add-sandbox-core.
 */
public final class PipelineLaw {

    /** One frozen law file: either its content, or the reason it could not be read at freeze time. */
    sealed interface Entry permits Content, Unreadable {}

    record Content(String text) implements Entry {}

    record Unreadable(String reason) implements Entry {}

    private final Map<String, Entry> byRef;

    /**
     * @param byRef the frozen entries keyed by the {@code .gnomish/}-relative reference;
     *     copied defensively so the law stays immutable after construction
     */
    PipelineLaw(Map<String, Entry> byRef) {
        this.byRef = Map.copyOf(byRef);
    }

    /**
     * Test/direct factory: a law whose every reference resolves to readable content.
     * Production law is built by {@link PipelineLawReader#freeze}; this exists so a
     * caller (or spec) can hand a reader the exact frozen content without a filesystem.
     *
     * @param content the frozen content keyed by {@code .gnomish/}-relative ref
     * @return an immutable law over {@code content}
     */
    public static PipelineLaw ofContent(Map<String, String> content) {
        return new PipelineLaw(content.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> new Content(e.getValue()))));
    }

    /**
     * The frozen content of the law file at {@code ref}.
     *
     * <p>Implements FR19, NFR-S2, D14 of add-sandbox-core.
     *
     * @param ref the {@code .gnomish/}-relative reference, exactly as the stage manifest
     *     declares it ({@code instructionsRef} or a judge check's {@code criteriaFile})
     * @return the file's content as frozen at invocation start; never null
     * @throws UnreadableLawFileException if {@code ref} was unreadable when the law was
     *     frozen, or names no law file this invocation captured
     */
    public String controlFile(String ref) {
        Entry entry = byRef.get(ref);
        return switch (entry) {
            case Content present -> present.text();
            case Unreadable unreadable -> throw new UnreadableLawFileException(ref, unreadable.reason());
            case null -> throw new UnreadableLawFileException(ref, "not part of the frozen pipeline law");
        };
    }
}
