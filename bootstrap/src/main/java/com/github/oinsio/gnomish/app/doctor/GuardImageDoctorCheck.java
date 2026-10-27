package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The egress guard image is present locally, when a stage is container-bound (FR1 of
 * add-doctor-command): one image inspection through the sandbox adapter's {@link
 * SandboxImageReadiness#inspectPresence}, no container and no pull (NFR-R1). An absent image only
 * warns: a box pulls it on first use. The inspection's throw is its answer (the owner's contract),
 * so it is caught here and its words — which may quote the runtime — rendered for the console
 * (NFR-O1).
 *
 * <p>Implements FR1, FR4, NFR-O1, NFR-R1 of add-doctor-command.
 */
@NullMarked
@Component
final class GuardImageDoctorCheck implements DoctorCheck {

    private final SandboxImageReadiness readiness;
    private final SandboxProperties sandbox;

    /**
     * @param readiness the sandbox adapter's image inspections
     * @param sandbox the operator sandbox config, read for the guard image
     */
    GuardImageDoctorCheck(SandboxImageReadiness readiness, SandboxProperties sandbox) {
        this.readiness = readiness;
        this.sandbox = sandbox;
    }

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.GUARD_IMAGE;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        return context.onContainer(id(), (clone, definition) -> List.of(present(sandbox.guardImage())));
    }

    private DoctorVerdict present(String image) {
        try {
            readiness.inspectPresence(image);
            return new DoctorVerdict(id(), DoctorStatus.OK, image + " is present locally", null);
        } catch (RuntimeException e) {
            return new DoctorVerdict(
                    id(),
                    DoctorStatus.WARN,
                    image + " is not present locally (" + DoctorText.words(e) + "); a box pulls it on first use",
                    "docker pull " + image);
        }
    }
}
