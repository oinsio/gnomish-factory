package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.subprocess.ExecutableVerdict;
import com.github.oinsio.gnomish.subprocess.HelperVerdict;
import com.github.oinsio.gnomish.subprocess.ProcessLaunch;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The production {@link HostLaunchAssessment}: the launch owner's own assessments, which start
 * nothing (design D4 of add-doctor-command).
 *
 * <p>Implements FR1, FR4 of add-doctor-command.
 */
@NullMarked
@Component
final class ProcessLaunchAssessment implements HostLaunchAssessment {

    @Override
    public HelperVerdict helper() {
        return ProcessLaunch.assessHelper();
    }

    @Override
    public ExecutableVerdict executable(String name, Path directory, @Nullable String path) {
        return ProcessLaunch.assessExecutable(name, directory, path);
    }
}
