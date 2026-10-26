package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.PreconditionVerdict;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * The results of an operator precondition verdict, for the three checks that read one from {@code
 * ServePreconditions} (FR1, FR4 of add-doctor-command): a held check passes; a refusal fails with
 * the check's own message, which names the refusal and its fix — the same reason the startup path
 * gives; a failure no precondition classifies fails with what the check threw.
 *
 * <p>Implements FR1, FR4, NFR-O1 of add-doctor-command.
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
     * @return one result
     */
    static List<DoctorVerdict> of(DoctorCheckId id, PreconditionVerdict verdict, String holds) {
        return List.of(
                switch (verdict) {
                    case PreconditionVerdict.Holds held -> new DoctorVerdict(id, DoctorStatus.OK, holds, null);
                    case PreconditionVerdict.Unmet unmet ->
                        new DoctorVerdict(
                                id, DoctorStatus.FAIL, unmet.unmet().reason().forConsole(), null);
                    case PreconditionVerdict.Failed failed ->
                        new DoctorVerdict(
                                id,
                                DoctorStatus.FAIL,
                                "the check could not judge: " + failed.failure().getMessage(),
                                null);
                });
    }
}
