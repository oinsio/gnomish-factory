package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.DoctorProjectReadings.StageBindings;
import com.github.oinsio.gnomish.app.project.ProjectResolution;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * What one {@code doctor} run read before its checks (design D3 of add-doctor-command): the
 * directory's resolution as the configuration loader found it, and — on a registered clone — the
 * pipeline law read once. It is the one place that decides when a check is skipped and says why:
 * no project resolves (FR2), the law did not load, or no stage binds the adapter the check is about.
 * The owners a check asks are its own fields; only the run's data travels here.
 *
 * <p>Implements FR1, FR2 of add-doctor-command.
 */
@NullMarked
public final class DoctorContext {

    /** A registered clone and its pipeline reading, which exist together or not at all. */
    private record Opened(RegisteredClone target, PipelineReading pipeline) {}

    private final @Nullable ProjectResolution project;
    private final @Nullable Opened opened;
    private final String noProject;

    private DoctorContext(@Nullable ProjectResolution project, @Nullable Opened opened, String noProject) {
        this.project = project;
        this.opened = opened;
        this.noProject = noProject;
    }

    /**
     * The context of a run in the directory {@code project} resolved to: on a registered clone its
     * pipeline is read once, through {@code reader}; otherwise every project check is skipped with
     * the directory's own answer.
     *
     * @param project the directory's resolution; {@code null} when none was published
     * @param reader reads a registered clone's pipeline law
     * @return the context; never null
     */
    public static DoctorContext of(
            @Nullable ProjectResolution project, Function<RegisteredClone, PipelineReading> reader) {
        return switch (project) {
            case ProjectResolution.Registered registered ->
                new DoctorContext(
                        project,
                        new Opened(registered.registeredClone(), reader.apply(registered.registeredClone())),
                        "");
            case ProjectResolution.Unregistered unregistered ->
                new DoctorContext(project, null, unregistered.dir() + " is not a registered clone");
            case ProjectResolution.Unread unread ->
                new DoctorContext(project, null, "the project registry could not be read for " + unread.dir());
            case null -> new DoctorContext(null, null, "no directory was resolved");
        };
    }

    /** The directory's resolution, or {@code null} when none was published. */
    public @Nullable ProjectResolution project() {
        return project;
    }

    /** Runs {@code check} on the registered clone, or skips it with the reason none resolves (FR2). */
    List<DoctorVerdict> onClone(DoctorCheckId id, Function<RegisteredClone, List<DoctorVerdict>> check) {
        return opened == null ? skipped(id, noProject) : check.apply(opened.target());
    }

    /** Runs {@code check} on the clone's pipeline reading, or skips it as {@link #onClone} does. */
    List<DoctorVerdict> onPipeline(DoctorCheckId id, Function<PipelineReading, List<DoctorVerdict>> check) {
        return opened == null ? skipped(id, noProject) : check.apply(opened.pipeline());
    }

    /** Runs {@code check} on the clone and its loaded definition, or skips it with the reason. */
    List<DoctorVerdict> onDefinition(
            DoctorCheckId id, BiFunction<RegisteredClone, PipelineDefinition, List<DoctorVerdict>> check) {
        return onBound(id, bindings -> true, "", check);
    }

    /** {@link #onDefinition}, run only when a stage binds the container adapter (design D3). */
    List<DoctorVerdict> onContainer(
            DoctorCheckId id, BiFunction<RegisteredClone, PipelineDefinition, List<DoctorVerdict>> check) {
        return onBound(id, StageBindings::container, "no stage binds the container adapter", check);
    }

    /** {@link #onDefinition}, run only when a stage binds the host adapter (design D3). */
    List<DoctorVerdict> onHost(
            DoctorCheckId id, BiFunction<RegisteredClone, PipelineDefinition, List<DoctorVerdict>> check) {
        return onBound(id, StageBindings::host, "no stage binds the host adapter", check);
    }

    private List<DoctorVerdict> onBound(
            DoctorCheckId id,
            Predicate<StageBindings> bound,
            String unbound,
            BiFunction<RegisteredClone, PipelineDefinition, List<DoctorVerdict>> check) {
        if (opened == null) {
            return skipped(id, noProject);
        }
        if (!(opened.pipeline() instanceof PipelineReading.Loaded loaded)) {
            return skipped(id, "the pipeline law did not load");
        }
        if (!bound.test(loaded.bindings())) {
            return skipped(id, unbound);
        }
        return check.apply(opened.target(), loaded.definition());
    }

    private static List<DoctorVerdict> skipped(DoctorCheckId id, String reason) {
        return List.of(new DoctorVerdict(id, DoctorStatus.SKIPPED, reason, null));
    }
}
