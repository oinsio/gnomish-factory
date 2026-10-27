package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.DoctorArguments;
import com.github.oinsio.gnomish.app.DoctorArgumentsParser;
import com.github.oinsio.gnomish.app.DoctorFailedException;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.config.OperatorLogFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
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
 * the next one still runs, so no check stops the run. Its line names the fault's type and words,
 * rendered for the console since an exception message may carry a tool's or the tracker's text
 * (NFR-O1), and sends the operator to this process's log file, where the throwable is recorded
 * whole (UX1). That record is INFO, not WARN: WARN reaches stdout, which under {@code --json} is
 * one document (D5), and the operator's call to act is the {@code fail} line itself. A check whose id is {@linkplain
 * DoctorCheckId#online() online} — the tracker read probe — runs only under {@code --online};
 * without it the check is not run, so {@code doctor} makes no network call (FR3, NG5).
 *
 * <p>The results are one {@link DoctorReport} (design D5): its text through the console's human
 * path, or under {@code --json} its document through the machine path, never both, so stdout under
 * {@code --json} is one document. A run with a {@code fail} result ends with {@link
 * DoctorFailedException} once the report is out, which the exit-code mapper turns into exit code 1
 * and the exception reporting leaves unprinted; any other run returns, and the process exits 0
 * (FR6).
 *
 * <p>Implements FR1, FR2, FR3, FR4, FR5, FR6, FR7, NFR-O1, UX1, UX2 of add-doctor-command.
 */
@NullMarked
@Component
public final class DoctorCommand {

    private static final Logger log = LoggerFactory.getLogger(DoctorCommand.class);

    private final DoctorArgumentsParser parser = new DoctorArgumentsParser();
    private final List<DoctorCheck> checks;
    private final DoctorSurvey survey;
    private final ObjectProvider<RegisteredClone> resolvedClone;
    private final ConsoleIO console;
    private final String logFileFix;

    /**
     * @param checks every doctor check, in any order
     * @param survey reads the run's context once, before the checks
     * @param resolvedClone the clone the configuration loader resolved from {@code --dir}; absent
     *     when no project registers the directory (FR2)
     * @param console the operator's console
     * @param environment the environment the configuration loader published this process's log
     *     file to ({@link OperatorLogFile})
     */
    public DoctorCommand(
            List<DoctorCheck> checks,
            DoctorSurvey survey,
            ObjectProvider<RegisteredClone> resolvedClone,
            ConsoleIO console,
            Environment environment) {
        this.checks =
                checks.stream().sorted(Comparator.comparing(DoctorCheck::id)).toList();
        this.survey = survey;
        this.resolvedClone = resolvedClone;
        this.console = console;
        this.logFileFix = "the full error is in " + OperatorLogFile.published(environment)
                + "; fix what it names, then run gnomish doctor again";
    }

    /**
     * Runs the checks and prints the report; a failing check stops no other check.
     *
     * @param args the raw application arguments, including the leading {@code doctor} token
     * @throws com.github.oinsio.gnomish.app.UsageException if an option is unknown or malformed,
     *     before any check runs
     * @throws DoctorFailedException after the report is printed, when a result is {@code fail}
     */
    public void run(ApplicationArguments args) {
        DoctorArguments arguments = parser.parse(args, resolvedClone.getIfAvailable());
        DoctorReport report = new DoctorReport(verdicts(arguments.online()));
        if (arguments.json()) {
            console.printMachine(report.json());
        } else {
            console.print(report.text());
        }
        if (report.failed() > 0) {
            throw new DoctorFailedException(report.failed());
        }
    }

    /**
     * Every check's results, in report order.
     *
     * @param online whether {@code --online} was given; without it an online check is not run
     */
    List<DoctorVerdict> verdicts(boolean online) {
        DoctorContext context = survey.survey();
        List<DoctorVerdict> all = new ArrayList<>();
        for (DoctorCheck check : checks) {
            if (online || !check.id().online()) {
                all.addAll(run(check, context));
            }
        }
        return all;
    }

    /**
     * One check's results; a check that throws is one failed result naming the log file, where the
     * throwable is recorded with its cause chain, and the run goes on.
     */
    private List<DoctorVerdict> run(DoctorCheck check, DoctorContext context) {
        try {
            return check.run(context);
        } catch (RuntimeException e) {
            log.info("doctor check {} could not run", check.id().id(), e);
            String detail = "the check could not run: " + DoctorText.fault(e);
            return List.of(new DoctorVerdict(check.id(), DoctorStatus.FAIL, detail, logFileFix));
        }
    }
}
