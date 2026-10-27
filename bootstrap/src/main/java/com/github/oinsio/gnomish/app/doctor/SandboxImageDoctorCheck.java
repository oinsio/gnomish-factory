package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.ServePreconditions;
import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The sandbox image is present locally with the factory's contract, when a stage is
 * container-bound (FR1 of add-doctor-command): {@link ServePreconditions#verdict} for {@link
 * DaemonConditionKind#IMAGE_CONTRACT}, which asks the readiness port's contract inspection alone —
 * one image inspection, no container (design D1). The box image's git floor is a container start,
 * so it is named as not probed on every line (NG1); {@code serve}, {@code take} and {@code run}
 * still refuse on it before any work.
 *
 * <p>Implements FR1, FR4, NFR-R1 of add-doctor-command.
 */
@NullMarked
@Component
final class SandboxImageDoctorCheck implements DoctorCheck {

    /** What every line of this check says of the box git floor (NG1). */
    static final String FLOOR_NOT_PROBED =
            "; the box image's git floor is not probed (a container start), startup still checks it";

    private final ServePreconditions preconditions;
    private final SandboxProperties sandbox;

    /**
     * @param preconditions the startup owner of the operator preconditions
     * @param sandbox the operator sandbox config, read for the configured image's name
     */
    SandboxImageDoctorCheck(ServePreconditions preconditions, SandboxProperties sandbox) {
        this.preconditions = preconditions;
        this.sandbox = sandbox;
    }

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.SANDBOX_IMAGE;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        return context.onContainer(
                id(),
                (clone, definition) -> PreconditionLines.of(
                                id(),
                                preconditions.verdict(DaemonConditionKind.IMAGE_CONTRACT, clone, definition),
                                sandbox.image() + " is present locally with the factory's image contract",
                                failedFix())
                        .stream()
                        .map(line -> new DoctorVerdict(
                                line.check(), line.status(), line.detail() + FLOOR_NOT_PROBED, line.fix()))
                        .toList());
    }

    /**
     * The fix of an inspection that could not judge — an image the runtime does not hold, or none
     * configured.
     */
    private String failedFix() {
        String image = sandbox.image();
        return image == null
                ? "set factory.sandbox.image in the operator configuration, then run gnomish doctor again"
                : "docker pull " + image
                        + ", or build it from docs/examples/sandbox-image/, then run gnomish doctor again";
    }
}
