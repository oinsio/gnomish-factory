package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.subprocess.HelperVerdict;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The host process-group launcher helper and the group sweep's signaller are present when a stage
 * is host-bound (FR1 of add-doctor-command): the launch owner's {@code assessHelper}, the verdict its
 * launch refuses on, which alone knows the platform's helper, the signaller and the package that
 * provides each (design D4). Skipped when no stage binds the host adapter (design D3).
 *
 * <p>Implements FR1, FR4 of add-doctor-command.
 */
@NullMarked
@Component
final class LauncherHelperDoctorCheck implements DoctorCheck {

    private final HostLaunchAssessment launch;

    /** @param launch the launch owner's assessments */
    LauncherHelperDoctorCheck(HostLaunchAssessment launch) {
        this.launch = launch;
    }

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.LAUNCHER_HELPER;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        return context.onHost(
                id(),
                (clone, definition) -> List.of(
                        switch (launch.helper()) {
                            case HelperVerdict.Present present ->
                                new DoctorVerdict(
                                        id(),
                                        DoctorStatus.OK,
                                        present.launcher() + " and " + present.kill() + " are present",
                                        null);
                            case HelperVerdict.Missing missing ->
                                new DoctorVerdict(id(), DoctorStatus.FAIL, missing.reason(), missing.install());
                        }));
    }
}
