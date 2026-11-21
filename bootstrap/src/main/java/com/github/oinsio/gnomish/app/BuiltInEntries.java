package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.agent.command.CommandExecutorFactory;
import com.github.oinsio.gnomish.adapter.agent.command.CommandStageExecutor;
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.FilesExistExecutor;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.check.ShellExecutor;
import com.github.oinsio.gnomish.adapter.check.http.HttpCheckProbe;
import com.github.oinsio.gnomish.adapter.check.http.HttpExecutor;
import com.github.oinsio.gnomish.adapter.scm.ScmConnection;
import com.github.oinsio.gnomish.adapter.scm.builtin.ScmBuiltIn;
import com.github.oinsio.gnomish.adapter.scm.builtin.ScmBuiltIns;
import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.ExecutorName;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The registry entries of the six built-ins for one run (design D18 of
 * migrate-verify-to-executor-contract, as amended 2026-11-10): the {@code command} stage built-in,
 * and each verdict built-in the law's checks name — with the long-lived collaborators of its run,
 * its executor bound at resolution. This is the one production place a verdict built-in in {@code
 * :adapters} is constructed, in its entry's binding ({@code ExecutorRegistryOwnerSpec}); the two SCM
 * built-ins are bound by their own entries' bindings in {@code :adapters:scm} ({@link ScmBuiltIns}).
 *
 * <p>Construction follows the law: a built-in no check names gets no entry, so a law without an SCM
 * check runs with neither SCM connection configured. An SCM entry is built over the connection run
 * assembly admitted and the described law carries; a run that runs no stage admitted none, and runs
 * no check either, so it gets no SCM entry.
 *
 * <p>The {@link SecretsProvider} is a member with no accessor (NFR-S1): it resolves the SCM tokens
 * here and is never handed on.
 *
 * <p>Implements FR1, FR6, FR13, NFR-S2 of migrate-verify-to-executor-contract; FR8 of
 * add-command-executor.
 */
final class BuiltInEntries {

    private final FilesExistCheckRunner filesExist;
    private final ShellCommandCheckRunner shell;
    private final HttpCheckProbe http;
    private final SecretsProvider secrets;
    private final InstantSource clock;
    private final ProgramBounds bounds;

    /**
     * @param filesExist the run's existence runner; never null
     * @param shell the run's command check runner; never null
     * @param http the run's guarded {@code http} judgement; never null
     * @param secrets the secrets port the SCM tokens resolve through; never null
     * @param clock the run's read-time source; never null
     * @param bounds the run's program bounds, whose overall bound a {@code command} stage declaring no
     *     {@code roundTimeout} is bounded by (FR7 of extract-agent-round-runner); never null
     */
    BuiltInEntries(
            FilesExistCheckRunner filesExist,
            ShellCommandCheckRunner shell,
            HttpCheckProbe http,
            SecretsProvider secrets,
            InstantSource clock,
            ProgramBounds bounds) {
        this.filesExist = filesExist;
        this.shell = shell;
        this.http = http;
        this.secrets = secrets;
        this.clock = clock;
        this.bounds = bounds;
    }

    /**
     * The built-ins' entries for a run of {@code definition}.
     *
     * @param definition the law the run is frozen from; never null
     * @param described the described law, carrying the admitted SCM connections; never null
     * @return the {@code command} entry, then one entry per built-in the checks name; never null
     * @throws com.github.oinsio.gnomish.adapter.scm.ScmTokenException when an admitted connection's
     *     token no longer resolves
     */
    List<RegistryEntry> of(PipelineDefinition definition, DescribedLaw described) {
        List<RegistryEntry> entries = new ArrayList<>();
        entries.add(
                RegistryEntry.stage(CommandStageExecutor.describeAnswer(), new CommandExecutorFactory(clock, bounds)));
        for (String name : named(definition)) {
            switch (name) {
                case BuiltInExecutors.FILES_EXIST ->
                    entries.add(check(
                            name,
                            FilesExistExecutor.describeAnswer(),
                            (check, ref, workspace) -> new FilesExistExecutor(filesExist, workspace)));
                case BuiltInExecutors.SHELL ->
                    entries.add(check(
                            name,
                            ShellExecutor.describeAnswer(),
                            (check, ref, workspace) -> new ShellExecutor(shell, check, workspace)));
                case BuiltInExecutors.HTTP ->
                    entries.add(check(
                            name,
                            HttpExecutor.describeAnswer(),
                            (check, ref, workspace) -> new HttpExecutor(http, check, workspace)));
                case BuiltInExecutors.GITHUB_ACTIONS ->
                    connection(described, name)
                            .map(admitted -> scm(name, ScmBuiltIns.actions(admitted, secrets, clock)))
                            .ifPresent(entries::add);
                case BuiltInExecutors.SCM_STATUS ->
                    connection(described, name)
                            .map(admitted -> scm(name, ScmBuiltIns.statuses(admitted, secrets)))
                            .ifPresent(entries::add);
                default -> {
                    // A law's program, or the command built-in a check cannot reach: not a verdict
                    // built-in's entry.
                }
            }
        }
        return entries;
    }

    /** Every executor name the law's checks name, in pipeline order. */
    private static Set<String> named(PipelineDefinition definition) {
        Set<String> names = new LinkedHashSet<>();
        for (StageDefinition stage : definition.stages()) {
            for (VerifyCheck check : stage.verify()) {
                if (check instanceof VerifyCheck.Executor executor) {
                    names.add(executor.executorName());
                }
            }
        }
        return names;
    }

    private static Optional<ScmConnection> connection(DescribedLaw described, String name) {
        return described
                .connectionOf(name)
                .map(admitted -> new ScmConnection(admitted.endpoint(), admitted.settings()));
    }

    private static RegistryEntry check(String name, Describe describe, CheckBinding binding) {
        return RegistryEntry.check(new ExecutorName(name), describe, binding);
    }

    private static RegistryEntry scm(String name, ScmBuiltIn builtIn) {
        return RegistryEntry.check(
                new ExecutorName(name), builtIn.describe(), (check, ref, workspace) -> builtIn.bind(check, workspace));
    }
}
