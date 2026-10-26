package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.project.RegisteredClone;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code gnomish doctor [--dir=<clone>] [--online] [--json]} into a {@link DoctorArguments}.
 * An unknown option is refused first (FR8 of fix-operator-blockers). {@code --dir} is not resolved
 * here: the configuration loader resolved it once (FR3, design D9 of add-project-registry), so the
 * {@code dir} component is the registered clone's path, or absent when no project registers the
 * directory (FR2 of add-doctor-command). Public because the {@code doctor} command lives in its own
 * package of the composition root (design D3 of add-doctor-command).
 *
 * <p>Implements FR1, FR7 of add-doctor-command; FR8 of fix-operator-blockers (through {@link
 * ArgumentsParsingSupport}).
 */
public final class DoctorArgumentsParser {

    private static final String DOCTOR_TOKEN = "doctor";
    private static final String DIR = "dir";
    private static final String ONLINE = "online";
    private static final String JSON = "json";

    /** Every option {@code doctor} accepts. */
    private static final List<String> ACCEPTED = List.of(DIR, ONLINE, JSON);

    /**
     * Refuses an unknown option, then reads the two switches.
     *
     * @param args the raw application arguments, including the leading {@code doctor} token
     * @param clone the registered clone the configuration loader resolved from {@code --dir}, or
     *     {@code null} when no project registers the directory
     * @return the validated flags
     * @throws UsageException if an option is unknown, or a switch is given a value
     */
    public DoctorArguments parse(ApplicationArguments args, @Nullable RegisteredClone clone) {
        ArgumentsParsingSupport.rejectUnknownOptions(args, DOCTOR_TOKEN, ACCEPTED, Map.of());
        return new DoctorArguments(
                clone == null ? null : clone.clonePath(), SwitchFlag.isOn(args, ONLINE), SwitchFlag.isOn(args, JSON));
    }
}
