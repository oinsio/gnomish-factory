package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.ServePreconditions;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The factory clone's ref backend (FR1 of add-doctor-command): {@link
 * ServePreconditions#refFormatVerdict}, the ref-format check the startup assessment and the take
 * and run bootstraps run, as a verdict (design D1). Skipped when no project resolves (FR2).
 *
 * <p>Implements FR1, FR2, FR4 of add-doctor-command.
 */
@NullMarked
@Component
final class RefFormatDoctorCheck implements DoctorCheck {

    private final ServePreconditions preconditions;

    /** @param preconditions the startup owner of the operator preconditions */
    RefFormatDoctorCheck(ServePreconditions preconditions) {
        this.preconditions = preconditions;
    }

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.REF_FORMAT;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        return context.onClone(
                id(),
                clone -> PreconditionLines.of(
                        id(),
                        preconditions.refFormatVerdict(clone),
                        clone.clonePath() + " passes the startup ref-format check",
                        "run git -C " + clone.clonePath() + " rev-parse --show-ref-format to see what git"
                                + " answers, fix what it names, then run gnomish doctor again"));
    }
}
