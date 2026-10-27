package com.github.oinsio.gnomish.app.doctor;

import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The registered clone's pipeline law loads and validates (FR1 of add-doctor-command): the reading
 * the run took once through the pipeline source and the binding resolver ({@link DoctorSurvey}),
 * one failed result per located error, each naming the file and line to correct. Skipped when no project resolves (FR2).
 *
 * <p>Implements FR1, FR2, FR4 of add-doctor-command.
 */
@NullMarked
@Component
final class PipelineDoctorCheck implements DoctorCheck {

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.PIPELINE;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        return context.onPipeline(id(), reading -> switch (reading) {
            case PipelineReading.Loaded loaded ->
                List.of(new DoctorVerdict(
                        id(),
                        DoctorStatus.OK,
                        "loads and validates: " + loaded.definition().stages().size() + " stages",
                        null));
            case PipelineReading.Unloaded unloaded ->
                unloaded.reasons().stream()
                        .map(reason -> new DoctorVerdict(id(), DoctorStatus.FAIL, reason, unloaded.fix()))
                        .toList();
        });
    }
}
