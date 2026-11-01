package com.github.oinsio.gnomish.app.conformance;

import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.RepositoryInit;
import com.github.oinsio.gnomish.app.ExecutorVerifyArguments;
import com.github.oinsio.gnomish.app.ExecutorVerifyArgumentsParser;
import com.github.oinsio.gnomish.app.ExecutorVerifyFailedException;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.subprocess.ProcessLaunch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code gnomish executor verify <program> [--pending-settings=<json>]} (FR12, UX2 of
 * define-executor-contract; design D19): the conformance kit against a candidate program, one line
 * per case on stdout, and {@link ExecutorVerifyFailedException} — exit code 1 — when a case failed.
 * The candidate runs as the factory runs a declared program on the host: through {@link
 * ProgramCandidate}, which wraps it in the factory's own adapter, each case in a git repository of
 * its own under one scratch directory, which is removed afterwards. Its child environment is the host
 * base set alone, its values from the secrets port: the candidate is granted no credential.
 *
 * <p>Implements FR12, UX2 of define-executor-contract.
 */
@NullMarked
@Component
public final class ExecutorVerifyCommand {

    private static final Logger log = LoggerFactory.getLogger(ExecutorVerifyCommand.class);

    private final ExecutorVerifyArgumentsParser parser = new ExecutorVerifyArgumentsParser();
    private final KitEquipment equipment;
    private final ConsoleIO console;

    /**
     * @param gitProcessRunner the factory's git runner, which initialises each case's working copy
     * @param timeEquipment the clock every bounded wait reads, and the poll wait's sleeper
     * @param secretsProvider the value source of the children's base environment
     * @param console where the report goes, on the human path
     */
    public ExecutorVerifyCommand(
            GitProcessRunner gitProcessRunner,
            TimeEquipment timeEquipment,
            SecretsProvider secretsProvider,
            ConsoleIO console) {
        this.equipment = new KitEquipment(
                timeEquipment,
                ChildEnvAllowlist.of(List.of(), List.of(), secretsProvider::find),
                ProcessLaunch.grouped(),
                new RepositoryInit(gitProcessRunner),
                RoundScratchDirectory.HOST_TEMP);
        this.console = console;
    }

    /**
     * Runs the kit and prints its report.
     *
     * @param args the raw application arguments, including the leading {@code executor} token
     * @throws com.github.oinsio.gnomish.app.UsageException if the command line is malformed
     * @throws ExecutorVerifyFailedException after the report is printed, when a case failed
     */
    public void run(ApplicationArguments args) {
        ExecutorVerifyArguments arguments = parser.parse(args);
        Path scratchRoot = scratchRoot();
        KitReport report;
        try {
            var candidate = new ProgramCandidate(
                    List.of(arguments.program()), arguments.pendingSettings(), scratchRoot, equipment);
            report = new ConformanceKit(
                            arguments.pendingSettings(), equipment.time().sleeper())
                    .run(candidate);
        } finally {
            remove(scratchRoot);
        }
        report.lines().forEach(line -> console.print(line + ConsoleIO.LINE_END));
        if (report.failures() > 0) {
            throw new ExecutorVerifyFailedException(report.failures());
        }
    }

    private static Path scratchRoot() {
        try {
            return Files.createTempDirectory("gnomish-conformance-");
        } catch (IOException e) {
            throw new UncheckedIOException("could not create the conformance kit's scratch directory", e);
        }
    }

    /** Best effort: a directory left behind is named in the log, and the report still goes out. */
    private static void remove(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException e) {
            log.info("the conformance kit's scratch directory {} was not removed whole", root, e);
        }
    }
}
