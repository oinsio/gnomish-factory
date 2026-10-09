package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.project.RegisteredClone;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code gnomish dashboard}'s command-line flags into a {@link DashboardArguments} (task
 * 4.1): {@code --dir} (the registered clone, defaulting to {@code .}), {@code
 * --out} (defaults to {@code null}, meaning the instance-directory default path — design D8), and
 * the {@code --watch} boolean flag (FR7). {@code --out} resolves through {@link
 * DashboardOutputFlag}, shared with {@code serve --dashboard-out}.
 *
 * <p>Implements FR1, FR7 of add-dashboard-page; FR3 of add-project-registry.
 */
final class DashboardArgumentsParser {

    private static final String DIR = "dir";
    private static final String OUT = "out";
    private static final String WATCH = "watch";
    private static final String DASHBOARD_TOKEN = "dashboard";

    /** Every option {@code dashboard} accepts (FR8 of fix-operator-blockers). */
    private static final List<String> ACCEPTED = List.of(DIR, OUT, WATCH);

    /**
     * Parses {@code args} into a validated {@link DashboardArguments}.
     *
     * @param args the raw application arguments, including the leading {@code dashboard} token
     * @param clone the registered clone the configuration loader resolved from {@code --dir}; the
     *     {@code dir} component is its path (FR3, design D9 of add-project-registry)
     * @return the validated flags
     */
    DashboardArguments parse(ApplicationArguments args, RegisteredClone clone) {
        ArgumentsParsingSupport.rejectUnknownOptions(args, DASHBOARD_TOKEN, ACCEPTED, Map.of());
        Path dir = clone.clonePath();
        Path out = DashboardOutputFlag.parse(args, OUT);
        boolean watch = args.containsOption(WATCH);
        return new DashboardArguments(dir, out, watch);
    }
}
