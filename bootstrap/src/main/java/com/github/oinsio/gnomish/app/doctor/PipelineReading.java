package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.DoctorProjectReadings.StageBindings;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * The registered clone's pipeline law as {@code doctor} read it once for the run (design D3 of
 * add-doctor-command): loaded and validated with its stage bindings resolved, or the located
 * reasons it did not. The pipeline check reports it; the checks that need the definition or a
 * binding read it.
 *
 * <p>Implements FR1 of add-doctor-command.
 */
@NullMarked
public sealed interface PipelineReading {

    /**
     * The law loaded and validated, and its stage bindings resolved.
     *
     * @param definition the validated definition
     * @param bindings which adapters its stages bind
     */
    record Loaded(PipelineDefinition definition, StageBindings bindings) implements PipelineReading {}

    /**
     * The law did not load, or its stage bindings did not resolve.
     *
     * @param reasons one console-ready line per located error
     * @param fix what fixes every one of them (UX1 of add-doctor-command)
     */
    record Unloaded(List<String> reasons, String fix) implements PipelineReading {

        public Unloaded {
            reasons = List.copyOf(reasons);
        }
    }
}
