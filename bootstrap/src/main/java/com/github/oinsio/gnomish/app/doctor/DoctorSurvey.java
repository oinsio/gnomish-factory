package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.DoctorProjectReadings;
import com.github.oinsio.gnomish.app.PipelineLoadFailedException;
import com.github.oinsio.gnomish.app.project.ProjectResolution;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Reads what a {@code doctor} run's checks share, once, before any of them (design D3 of
 * add-doctor-command): the directory's resolution the configuration loader published, and on a
 * registered clone its pipeline law, loaded and validated and its stage bindings resolved through
 * the owners {@link DoctorProjectReadings} asks. A law that does not load is a reading, not a
 * refusal: the pipeline check reports it, each reason rendered for the console and with its fix
 * (NFR-O1, UX1), and the checks that need the law are skipped (FR1, FR7).
 *
 * <p>Implements FR1, FR2, FR4, NFR-O1, UX1 of add-doctor-command.
 */
@NullMarked
@Component
public final class DoctorSurvey {

    /** The fix of a law that loaded with located errors. */
    static final String LAW_FIX =
            "correct the pipeline law at the location each line names, then run gnomish doctor again";

    /** The fix of stage bindings the operator configuration does not resolve. */
    static final String BINDINGS_FIX =
            "correct factory.bindings in the operator configuration as the line names, then run gnomish doctor again";

    private final ObjectProvider<ProjectResolution> resolution;
    private final DoctorProjectReadings readings;

    /**
     * @param resolution the directory's resolution, published by the configuration loader for
     *     {@code doctor}; absent for every other command
     * @param readings the owners a registered clone's law is read through
     */
    public DoctorSurvey(ObjectProvider<ProjectResolution> resolution, DoctorProjectReadings readings) {
        this.resolution = resolution;
        this.readings = readings;
    }

    /** The run's context. */
    public DoctorContext survey() {
        return DoctorContext.of(resolution.getIfAvailable(), this::read);
    }

    /** The clone's law as one reading: loaded with its bindings, or every reason it was not. */
    PipelineReading read(RegisteredClone clone) {
        PipelineDefinition definition;
        try {
            definition = readings.loadPipeline(clone);
        } catch (PipelineLoadFailedException e) {
            return new PipelineReading.Unloaded(e.renderedErrors(), LAW_FIX);
        } catch (IOException e) {
            return new PipelineReading.Unloaded(
                    List.of("the pipeline law could not be read: " + DoctorText.fault(e)),
                    "make the pipeline law of " + clone.clonePath() + " readable, then run gnomish doctor again");
        }
        try {
            return new PipelineReading.Loaded(definition, readings.bindings(definition));
        } catch (IllegalArgumentException e) {
            return new PipelineReading.Unloaded(
                    List.of("the stage bindings do not resolve: " + DoctorText.words(e)), BINDINGS_FIX);
        }
    }
}
