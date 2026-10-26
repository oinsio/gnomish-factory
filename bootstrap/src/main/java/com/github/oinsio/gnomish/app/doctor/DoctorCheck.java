package com.github.oinsio.gnomish.app.doctor;

import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * One readiness check of {@code doctor} (design D3 of add-doctor-command): a thin adapter that asks
 * the owner the startup path asks and turns its answer into results, refusing nothing and starting
 * nothing an owner would not (FR4, NFR-R1). A check that needs the project, the pipeline or a
 * binding asks {@link DoctorContext} for it and is skipped with the reason when it is not there.
 *
 * <p>Implements FR1, FR2, FR4 of add-doctor-command.
 */
@NullMarked
public interface DoctorCheck {

    /** Which check this is. */
    DoctorCheckId id();

    /**
     * Runs the check.
     *
     * @param context the run's project and pipeline readings; never null
     * @return one or more results, all under {@link #id()}; never empty
     */
    List<DoctorVerdict> run(DoctorContext context);
}
