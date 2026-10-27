package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.project.ProjectResolution;
import com.github.oinsio.gnomish.logtext.LogText;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The project registration of the directory {@code doctor} works in (FR1, FR2 of
 * add-doctor-command): the one {@code ProjectRegistry.resolve} answer the configuration loader read
 * for the process. A directory {@code --dir} named that no project registers fails with the
 * registry's own refusal, which names the {@code project add} line; the working directory outside
 * any clone is no failure — the project checks are skipped, and this line says why, with the same
 * {@code project add} line (FR2). The refusal names the operator's directory, so it is rendered for
 * the console (NFR-O1); a failed line's fix points at the {@code project add} line it names (UX1).
 *
 * <p>Implements FR1, FR2, FR4, NFR-O1, UX1 of add-doctor-command.
 */
@NullMarked
@Component
final class RegistrationDoctorCheck implements DoctorCheck {

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.REGISTRATION;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        return List.of(
                switch (context.project()) {
                    case ProjectResolution.Registered registered ->
                        new DoctorVerdict(
                                id(),
                                DoctorStatus.OK,
                                registered.registeredClone().clonePath() + " is clone "
                                        + registered.registeredClone().cloneName() + " of project "
                                        + registered.registeredClone().project(),
                                null);
                    case ProjectResolution.Unregistered unregistered ->
                        new DoctorVerdict(
                                id(),
                                unregistered.named() ? DoctorStatus.FAIL : DoctorStatus.SKIPPED,
                                LogText.forConsole(unregistered.refusal()),
                                unregistered.named() ? DoctorText.AS_THE_LINE_NAMES : null);
                    case ProjectResolution.Unread unread ->
                        new DoctorVerdict(
                                id(),
                                unread.named() ? DoctorStatus.FAIL : DoctorStatus.SKIPPED,
                                "the project registry could not be read, so " + unread.dir() + " could not be judged",
                                "correct the project file the operator configuration line names");
                    case null ->
                        new DoctorVerdict(id(), DoctorStatus.SKIPPED, "no directory was resolved in this run", null);
                });
    }
}
