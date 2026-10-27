package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.ServePreconditions;
import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * Docker reachable when a stage is container-bound (FR1 of add-doctor-command): {@link
 * ServePreconditions#verdict} for {@link DaemonConditionKind#CONTAINER_RUNTIME} — the execution
 * plan the startup path makes, which asks the {@code ContainerRuntimeProbe} — so the reason is the
 * startup path's own, its configuration refusals included (design D1). Skipped when no stage binds
 * the container adapter (design D3).
 *
 * <p>Implements FR1, FR4 of add-doctor-command.
 */
@NullMarked
@Component
final class DockerDoctorCheck implements DoctorCheck {

    /** The fix of a runtime check that could not judge. */
    static final String FAILED_FIX = "start Docker, or set DOCKER_HOST to a runtime that answers, and correct the"
            + " factory.sandbox setting the line names, then run gnomish doctor again";

    private final ServePreconditions preconditions;

    /** @param preconditions the startup owner of the operator preconditions */
    DockerDoctorCheck(ServePreconditions preconditions) {
        this.preconditions = preconditions;
    }

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.DOCKER;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        return context.onContainer(
                id(),
                (clone, definition) -> PreconditionLines.of(
                        id(),
                        preconditions.verdict(DaemonConditionKind.CONTAINER_RUNTIME, clone, definition),
                        "the Docker runtime answers",
                        FAILED_FIX));
    }
}
