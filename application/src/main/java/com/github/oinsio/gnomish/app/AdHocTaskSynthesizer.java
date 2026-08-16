package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Turns a validated {@link RunArguments} and the loaded {@link PipelineDefinition} into the
 * initial {@link TaskContext}/{@link TaskState} pair the engine starts from (FR2, design D4,
 * task 7.3 scope): generates or adopts the task id, splits the task description text into
 * title/body, and resolves the starting stage — {@code --from-stage} when present and valid,
 * else the pipeline's first stage.
 *
 * <p>The task description text comes from {@link RunArguments#taskSource()}: inline text
 * verbatim, or a {@code --task-file} path read from disk here — title/body splitting is the
 * same operation regardless of source (FR2).
 *
 * <p>Implements FR1, FR2, D4 of add-manual-run.
 */
public final class AdHocTaskSynthesizer {

    private static final DateTimeFormatter ID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String ID_CHARSET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_SUFFIX_LENGTH = 2;

    private final Clock clock;
    private final Random random;

    /**
     * @param clock the time source for the generated id's timestamp component; a fixed clock
     *     makes generation deterministic for tests (FR2)
     * @param random the source of the generated id's 2-character suffix; a seeded instance
     *     makes generation deterministic for tests (FR2)
     */
    public AdHocTaskSynthesizer(Clock clock, Random random) {
        this.clock = clock;
        this.random = random;
    }

    /**
     * Synthesizes the ad-hoc task's initial context and state from {@code args} and the
     * loaded {@code definition}.
     *
     * <p>Implements FR1, FR2, D4 of add-manual-run.
     *
     * @param args the parsed and first-tier-validated run arguments (task 7.1)
     * @param definition the pipeline definition loaded from {@code --dir}'s
     *     {@code .gnomish/} (task 7.2), whose stage order supplies the default start and
     *     whose stage names validate {@code --from-stage}
     * @return the synthesized task context and its initial state
     * @throws IOException if {@code args.taskSource()} is a {@link TaskSource.FromFile} whose
     *     path cannot be read
     * @throws UsageException if {@code --from-stage} names a stage absent from
     *     {@code definition}, listing the known stage names (D4)
     * @throws IllegalStateException if {@code args.taskSource()} is {@code null} — that is, if
     *     {@code args} came from a {@code --resume} invocation; ad-hoc synthesis is only for
     *     fresh runs. Resume bootstrap (add-git-workflow FR8, task 4.6) reads the task from the
     *     branch instead of calling this method, and is not wired yet.
     */
    public SynthesizedTask synthesize(RunArguments args, PipelineDefinition definition) throws IOException {
        String taskId = args.taskId() != null ? args.taskId() : generateTaskId();
        TaskSource taskSource = args.taskSource();
        if (taskSource == null) {
            throw new IllegalStateException(
                    "AdHocTaskSynthesizer.synthesize requires a non-null taskSource; --resume runs are not"
                            + " wired to this path yet (add-git-workflow task 4.6)");
        }
        String text = readDescription(taskSource);
        Split split = splitTitleAndBody(text);
        String startStage = resolveStartStage(args.fromStage(), definition);

        TaskContext context = new TaskContext(taskId, split.title(), split.body(), List.of());
        TaskState initialState = TaskState.atStageStart(startStage);
        return new SynthesizedTask(context, initialState);
    }

    private String generateTaskId() {
        String timestamp = ID_TIMESTAMP.format(clock.instant().atZone(clock.getZone()));
        StringBuilder suffix = new StringBuilder(ID_SUFFIX_LENGTH);
        for (int i = 0; i < ID_SUFFIX_LENGTH; i++) {
            suffix.append(ID_CHARSET.charAt(random.nextInt(ID_CHARSET.length())));
        }
        return "manual-" + timestamp + "-" + suffix;
    }

    private static String readDescription(TaskSource source) throws IOException {
        return switch (source) {
            case TaskSource.Inline(String text) -> text;
            case TaskSource.FromFile(var path) -> Files.readString(path);
        };
    }

    /**
     * Splits {@code text} into a title (the first non-empty line, leading markdown heading
     * markers and surrounding whitespace stripped) and a body (the remainder, with a single
     * leading blank line trimmed off). Entirely blank text yields an empty title and body
     * (FR2) — {@link TaskContext} permits empty title/body, so this is a legal, deliberate
     * fallback rather than an error.
     */
    private static Split splitTitleAndBody(String text) {
        List<String> lines = text.lines().toList();
        int titleIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                titleIndex = i;
                break;
            }
        }
        if (titleIndex == -1) {
            return new Split("", "");
        }
        String title = lines.get(titleIndex).replaceFirst("^#+\\s*", "").strip();
        String body =
                String.join("\n", lines.subList(titleIndex + 1, lines.size())).strip();
        return new Split(title, body);
    }

    private static String resolveStartStage(@Nullable String fromStage, PipelineDefinition definition) {
        List<StageDefinition> stages = definition.stages();
        if (fromStage == null) {
            return stages.get(0).name();
        }
        boolean known = stages.stream().anyMatch(stage -> stage.name().equals(fromStage));
        if (!known) {
            String knownNames = stages.stream().map(StageDefinition::name).collect(Collectors.joining(", "));
            throw new UsageException(
                    "--from-stage=" + fromStage + " names an unknown stage; known stages: " + knownNames);
        }
        return fromStage;
    }

    private record Split(String title, String body) {}

    /**
     * The synthesized ad-hoc task, ready for the engine's first run: {@code context} carries
     * the id/title/body/decisions, {@code initialState} positions it at the resolved starting
     * stage with no attempts burned (FR2, D4).
     *
     * @param context the synthesized task identity and description
     * @param initialState the initial engine state, positioned at the resolved start stage
     */
    public record SynthesizedTask(TaskContext context, TaskState initialState) {}
}
