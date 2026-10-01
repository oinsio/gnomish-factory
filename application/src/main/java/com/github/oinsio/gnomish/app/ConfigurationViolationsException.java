package com.github.oinsio.gnomish.app;

import java.io.Serial;
import java.util.List;
import org.springframework.boot.ExitCodeGenerator;

/**
 * Startup stopped on the operator configuration: every violation the configuration loader found in
 * one run — a key where its level forbids, an unknown key, a {@code FACTORY_*} variable, a file
 * others may write, an unregistered {@code --dir} — each as one line naming its location, the key,
 * the reason and the fix (FR7, NFR-O2, UX1 of add-project-registry).
 *
 * <p>Raised while Spring Boot prepares the environment, before the application context exists: no
 * exit-code mapper bean can see it, so it carries its exit code itself — 2, the usage-error code
 * (design D6). The loader prints the report before throwing; the exception belongs to {@link
 * RunExceptionReporting}'s "prints its own message" family, so {@link
 * ReportedFailureExceptionReporter} claims it and Spring prints no stack trace.
 *
 * <p>Implements FR7, NFR-O2, UX1 of add-project-registry.
 */
public final class ConfigurationViolationsException extends RuntimeException implements ExitCodeGenerator {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The usage-error exit code (FR7: no new code is introduced). */
    static final int USAGE_EXIT_CODE = 2;

    /** Transient: the lines are the message, which serialization already carries. */
    private final transient List<String> violations;

    /**
     * @param violations one line per violation, in the order found; at least one
     * @throws IllegalArgumentException if {@code violations} is empty
     */
    public ConfigurationViolationsException(List<String> violations) {
        super(report(violations));
        this.violations = List.copyOf(violations);
    }

    /** Every violation line, in the order found. */
    public List<String> violations() {
        return violations;
    }

    @Override
    public int getExitCode() {
        return USAGE_EXIT_CODE;
    }

    private static String report(List<String> violations) {
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("a configuration violation report needs at least one violation");
        }
        StringBuilder report = new StringBuilder("gnomish did not start: ")
                .append(violations.size())
                .append(violations.size() == 1 ? " configuration problem" : " configuration problems")
                .append(" (fix every line, then run the command again)");
        violations.forEach(line -> report.append("\n  - ").append(line));
        return report.toString();
    }
}
