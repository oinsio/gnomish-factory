package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.DoctorArgumentsParser;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code gnomish doctor [--dir=<clone>] [--online] [--json]}: every readiness check in one run,
 * refusing on none of them (FR1, FR7 of add-doctor-command). {@code ManualRunRunner} dispatches it
 * before the git floor refusal every other subcommand passes through (design D2), so a git below
 * the floor is reported as a check rather than ending the process; the configuration loader ran in
 * collect mode for it, so a configuration violation is no refusal either.
 *
 * <p>The checks are the {@link DoctorCheck} beans, one per {@link DoctorCheckId}, run in that
 * enum's order over one {@link DoctorContext} the {@link DoctorSurvey} read first (design D3). Each
 * check asks the owner the startup path asks (FR4); a check that throws is reported as failed and
 * the next one still runs, so no check stops the run. {@code --online} will add the tracker read
 * probe (FR3, task 3.3 of add-doctor-command); until the renderer of task 3.4 replaces it, each
 * result is one plain line through the console's human path.
 *
 * <p>Implements FR1, FR2, FR4, FR7 of add-doctor-command.
 */
@NullMarked
@Component
public final class DoctorCommand {

    private final DoctorArgumentsParser parser = new DoctorArgumentsParser();
    private final List<DoctorCheck> checks;
    private final DoctorSurvey survey;
    private final ObjectProvider<RegisteredClone> resolvedClone;
    private final ConsoleIO console;

    /**
     * @param checks every doctor check, in any order
     * @param survey reads the run's context once, before the checks
     * @param resolvedClone the clone the configuration loader resolved from {@code --dir}; absent
     *     when no project registers the directory (FR2)
     * @param console the operator's console
     */
    public DoctorCommand(
            List<DoctorCheck> checks,
            DoctorSurvey survey,
            ObjectProvider<RegisteredClone> resolvedClone,
            ConsoleIO console) {
        this.checks =
                checks.stream().sorted(Comparator.comparing(DoctorCheck::id)).toList();
        this.survey = survey;
        this.resolvedClone = resolvedClone;
        this.console = console;
    }

    /**
     * Runs the checks and reports each one; a failing check ends nothing.
     *
     * @param args the raw application arguments, including the leading {@code doctor} token
     * @throws com.github.oinsio.gnomish.app.UsageException if an option is unknown or malformed
     */
    public void run(ApplicationArguments args) {
        // FR3: the parsed arguments' online() adds the tracker read probe (task 3.3 of add-doctor-command).
        parser.parse(args, resolvedClone.getIfAvailable());
        for (DoctorVerdict verdict : verdicts()) {
            console.print(line(verdict));
        }
    }

    /** Every check's results, in report order. */
    List<DoctorVerdict> verdicts() {
        DoctorContext context = survey.survey();
        List<DoctorVerdict> all = new ArrayList<>();
        for (DoctorCheck check : checks) {
            all.addAll(run(check, context));
        }
        return all;
    }

    /** One check's results; a check that throws is one failed result, and the run goes on. */
    private static List<DoctorVerdict> run(DoctorCheck check, DoctorContext context) {
        try {
            return check.run(context);
        } catch (RuntimeException e) {
            return List.of(new DoctorVerdict(
                    check.id(), DoctorStatus.FAIL, "the check could not run: " + e.getMessage(), null));
        }
    }

    /** The provisional line: status, name and detail, and the fix on its own line below. */
    private static String line(DoctorVerdict verdict) {
        String head = verdict.status().name().toLowerCase(Locale.ROOT) + "  "
                + verdict.check().displayName() + ": " + verdict.detail() + ConsoleIO.LINE_END;
        return verdict.fix() == null ? head : head + "      fix: " + verdict.fix() + ConsoleIO.LINE_END;
    }
}
