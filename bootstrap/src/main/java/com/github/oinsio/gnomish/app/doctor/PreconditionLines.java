package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.PreconditionVerdict;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * The results of an operator precondition verdict, for the three checks that read one from {@code
 * ServePreconditions} (FR1, FR4 of add-doctor-command): a held check passes; a refusal fails with
 * the check's own message, which names the refusal and its fix — the same reason the startup path
 * gives — so its fix points at it; a failure no precondition classifies fails with what the check
 * threw, rendered for the console (NFR-O1), and the fix the calling check names for its subject
 * (UX1).
 *
 * <p>Implements FR1, FR4, NFR-O1, UX1 of add-doctor-command.
 */
@NullMarked
final class PreconditionLines {

    private PreconditionLines() {}

    /**
     * The result of {@code verdict} for {@code id}.
     *
     * @param id the check
     * @param verdict the owner's verdict
     * @param holds the detail of a held check
     * @param failedFix the fix of a failure no precondition classifies
     * @return one result
     */
    static List<DoctorVerdict> of(DoctorCheckId id, PreconditionVerdict verdict, String holds, String failedFix) {
        return List.of(
                switch (verdict) {
                    case PreconditionVerdict.Holds held -> new DoctorVerdict(id, DoctorStatus.OK, holds, null);
                    case PreconditionVerdict.Unmet unmet ->
                        new DoctorVerdict(
                                id,
                                DoctorStatus.FAIL,
                                unmet.unmet().reason().forConsole(),
                                DoctorText.AS_THE_LINE_NAMES);
                    case PreconditionVerdict.Failed failed ->
                        new DoctorVerdict(
                                id,
                                DoctorStatus.FAIL,
                                "the check could not judge: " + DoctorText.words(failed.failure()),
                                failedFix);
                });
    }
}
