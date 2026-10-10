package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.project.RegisteredClone;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code gnomish serve}'s command-line flags into a {@link ServeArguments} (task 5.1 of
 * add-factory-serve): a required-nothing {@code --dir} (the registered clone, defaulting to
 * {@code .}), a positive-only {@code --slots} override of {@code
 * ServeProperties#slots()} (design D3), the {@code --drain} flag, and the embedded dashboard's
 * {@code --dashboard} and {@code --dashboard-out} (design D12 of
 * supervise-daemon-loops-and-embed-dashboard).
 *
 * <p>{@code serve} has no ad-hoc task source and no {@code <ref>} — it works the whole ready
 * queue, not one task — so every flag meaningful only to a single-task {@code take} invocation is
 * rejected up front, before the tracker is ever touched, mirroring {@link
 * TakeArgumentsParser}'s "Flag validation" refusal: {@code --mode}, {@code --task}, {@code
 * --task-file}, {@code --task-id}, {@code --from-stage}, {@code --resume}, {@code --base}, {@code
 * --discard-work}, {@code --takeover}.
 *
 * <p>The effective dashboard switch — the flag or the configured {@code factory.serve.dashboard} —
 * is folded here and only here: {@link ServeArguments#dashboard()} carries it, and {@code
 * --dashboard-out} while it is off is refused before the tracker is touched.
 *
 * <p>Implements FR2, FR4, D3 of add-factory-serve; FR3 of add-project-registry; FR8 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
final class ServeArgumentsParser {

    private static final String SERVE_TOKEN = "serve";
    private static final String DIR = "dir";
    private static final String SLOTS = "slots";
    private static final String DRAIN = "drain";
    private static final String DASHBOARD = "dashboard";
    private static final String DASHBOARD_OUT = "dashboard-out";

    /** Flags {@code serve} never accepts: {@code take}'s single-task flag set (see class doc). */
    private static final List<String> REJECTED_FLAGS =
            List.of("mode", "task", "task-file", "task-id", "from-stage", "resume", "base", "discard-work", "takeover");

    /**
     * Every option {@code serve} knows (FR8 of fix-operator-blockers): its own flags plus {@link
     * #REJECTED_FLAGS}, which stay known so their specific refusal wins over the generic one.
     */
    private static final List<String> ACCEPTED = Stream.concat(
                    Stream.of(DIR, SLOTS, DRAIN, DASHBOARD, DASHBOARD_OUT), REJECTED_FLAGS.stream())
            .toList();

    /**
     * @param args the raw application arguments, including the leading {@code serve} token
     * @param clone the registered clone the configuration loader resolved from {@code --dir}; the
     *     {@code dir} component is its path (FR3, design D9 of add-project-registry)
     * @param dashboardConfigured the configured {@code factory.serve.dashboard}, which turns the
     *     dashboard on without the flag (FR8 of supervise-daemon-loops-and-embed-dashboard)
     * @return the validated flags
     * @throws UsageException if a rejected flag is present, {@code --slots} is given but is not a
     *     positive integer, or {@code --dashboard-out} is given while the dashboard is off
     */
    ServeArguments parse(ApplicationArguments args, RegisteredClone clone, boolean dashboardConfigured) {
        ArgumentsParsingSupport.rejectUnknownOptions(args, SERVE_TOKEN, ACCEPTED, Map.of());
        rejectInapplicableFlags(args);
        Path dir = clone.clonePath();
        Integer slots = parseSlots(args);
        boolean drain = SwitchFlag.isOn(args, DRAIN);
        boolean dashboard = SwitchFlag.isOn(args, DASHBOARD) || dashboardConfigured;
        Path dashboardOut = parseDashboardOut(args, dashboard);
        return new ServeArguments(dir, slots, drain, dashboard, dashboardOut);
    }

    private @Nullable Path parseDashboardOut(ApplicationArguments args, boolean dashboard) {
        Path out = DashboardOutputFlag.parse(args, DASHBOARD_OUT);
        if (out != null && !dashboard) {
            throw new UsageException("--" + DASHBOARD_OUT + " needs the dashboard on: pass --" + DASHBOARD
                    + " or set factory.serve.dashboard: true");
        }
        return out;
    }

    private void rejectInapplicableFlags(ApplicationArguments args) {
        for (String flag : REJECTED_FLAGS) {
            if (args.containsOption(flag)) {
                throw new UsageException("--" + flag + " is not accepted by 'gnomish serve': serve has no"
                        + " single-task flags and no --mode/--resume");
            }
        }
    }

    private @Nullable Integer parseSlots(ApplicationArguments args) {
        String value = ArgumentsParsingSupport.singleValue(args, SLOTS);
        if (value == null) {
            return null;
        }
        int slots;
        try {
            slots = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new UsageException("--slots must be a positive integer, got '" + value + "'");
        }
        if (slots <= 0) {
            throw new UsageException("--slots must be positive, got " + slots);
        }
        return slots;
    }
}
