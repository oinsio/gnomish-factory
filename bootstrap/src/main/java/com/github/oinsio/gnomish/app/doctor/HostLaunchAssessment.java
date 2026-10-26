package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.subprocess.ExecutableVerdict;
import com.github.oinsio.gnomish.subprocess.HelperVerdict;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The host launch owner's two non-refusing assessments, as {@code doctor} asks them (design D4 of
 * add-doctor-command): a role interface of this module over {@code ProcessLaunch.assessHelper} and
 * {@code ProcessLaunch.assessExecutable}, so a check's spec doubles a type it owns rather than a
 * static of the launch owner. {@link ProcessLaunchAssessment} is the one production form.
 *
 * <p>Implements FR1, FR4 of add-doctor-command.
 */
@NullMarked
public interface HostLaunchAssessment {

    /** The launch owner's helper verdict: the platform's launcher and the group sweep's kill. */
    HelperVerdict helper();

    /**
     * What {@code name} resolves to under the launch owner's rule.
     *
     * @param name the command's first word
     * @param directory the child's working directory
     * @param path the {@code PATH} the child's environment names, or {@code null} for none
     */
    ExecutableVerdict executable(String name, Path directory, @Nullable String path);
}
