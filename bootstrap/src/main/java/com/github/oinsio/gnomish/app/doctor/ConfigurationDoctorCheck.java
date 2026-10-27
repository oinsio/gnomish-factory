package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.ConfigurationViolations;
import com.github.oinsio.gnomish.app.OperatorConfigLoader;
import com.github.oinsio.gnomish.logtext.LogText;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The operator configuration (FR1, FR7 of add-doctor-command): one failed result per violation the
 * configuration loader collected for {@code doctor} instead of refusing ({@value
 * OperatorConfigLoader#VIOLATIONS_BEAN}, design D1) — the same lines the refusing commands stop
 * on, each naming its location, the key, the reason and the fix. The check reads the published
 * violations, never a configuration file. A line may quote a key or a value from the file, so it is
 * rendered for the console (NFR-O1); its fix points at the one the line names (UX1).
 *
 * <p>Implements FR1, FR4, FR7, NFR-O1, UX1 of add-doctor-command.
 */
@NullMarked
@Component
final class ConfigurationDoctorCheck implements DoctorCheck {

    private final ObjectProvider<ConfigurationViolations> violations;

    /**
     * @param violations the violations the loader published; absent unless the command is {@code
     *     doctor}
     */
    ConfigurationDoctorCheck(ObjectProvider<ConfigurationViolations> violations) {
        this.violations = violations;
    }

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.CONFIGURATION;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        ConfigurationViolations collected = violations.getIfAvailable();
        if (collected == null) {
            return List.of(new DoctorVerdict(
                    id(), DoctorStatus.SKIPPED, "the configuration was not collected in this run", null));
        }
        if (collected.isEmpty()) {
            return List.of(new DoctorVerdict(id(), DoctorStatus.OK, "no violation", null));
        }
        return collected.lines().stream()
                .map(line -> new DoctorVerdict(
                        id(), DoctorStatus.FAIL, LogText.forConsole(line), DoctorText.AS_THE_LINE_NAMES))
                .toList();
    }
}
