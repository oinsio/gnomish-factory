package com.github.oinsio.gnomish.app;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code --mode} and validates the exclusion matrix between {@code --resume}, the
 * git-only flags ({@code --base}, {@code --resume}, {@code --discard-work}) and the rest of
 * {@code gnomish run}'s flags (FR7, FR8, design D7, D9, D10 of add-git-workflow). Split out of
 * {@link RunArgumentsParser} to keep that class within the project's file-size target
 * (`.claude/rules/process-invariants.md`).
 *
 * <p>Rules enforced, each a {@link UsageException} (exit code 2):
 *
 * <ul>
 *   <li>{@code --resume} together with {@code --task}, {@code --task-file}, {@code --task-id}, or
 *       {@code --from-stage} — resume reads task identity from the branch, not from these flags
 *       (FR8).
 *   <li>Any of {@code --base}, {@code --resume}, {@code --discard-work} together with {@code
 *       --mode=in-place} — git-only flags require git mode (FR7, design D8).
 *   <li>{@code --discard-work} without {@code --resume} — replaying "the interrupted round" (D10)
 *       presupposes a round recorded on a branch being resumed; a fresh run has no interrupted
 *       round to replay.
 * </ul>
 *
 * <p>Implements FR7, FR8, design D7, D9, D10 of add-git-workflow.
 */
final class GitFlagsValidator {

    private static final String MODE_GIT = "git";
    private static final String MODE_IN_PLACE = "in-place";

    private GitFlagsValidator() {}

    /**
     * Parses {@code --mode}'s single value into its {@link RunArguments.Mode} (FR7, design D8 of
     * add-git-workflow): {@code null} (flag absent) &rarr; {@code GIT} (the default, safer
     * mode); {@code "git"} / {@code "in-place"} &rarr; the matching mode. Any other value is a
     * usage error naming the accepted forms.
     */
    static RunArguments.Mode parseMode(@Nullable String value) {
        if (value == null) {
            return RunArguments.Mode.GIT;
        }
        return switch (value) {
            case MODE_GIT -> RunArguments.Mode.GIT;
            case MODE_IN_PLACE -> RunArguments.Mode.IN_PLACE;
            default ->
                throw new UsageException(
                        "--mode=" + value + " is invalid: accepted forms are --mode=git or --mode=in-place");
        };
    }

    static void validate(
            RunArguments.Mode mode,
            @Nullable String resume,
            @Nullable String base,
            boolean discardWork,
            ApplicationArguments args) {
        if (resume != null) {
            requireAbsent(args, "task", resume);
            requireAbsent(args, "task-file", resume);
            requireAbsent(args, "task-id", resume);
            requireAbsent(args, "from-stage", resume);
        }
        if (mode == RunArguments.Mode.IN_PLACE) {
            requireGitOnlyAbsentWithInPlace(base != null, "--base");
            requireGitOnlyAbsentWithInPlace(resume != null, "--resume");
            requireGitOnlyAbsentWithInPlace(discardWork, "--discard-work");
        }
        if (discardWork && resume == null) {
            throw new UsageException("--discard-work requires --resume=<task>: it replays an interrupted round of a"
                    + " resumed task, not a fresh run");
        }
    }

    private static void requireAbsent(ApplicationArguments args, String flagName, String resume) {
        if (args.containsOption(flagName)) {
            throw new UsageException("--resume=" + resume + " cannot be combined with --" + flagName
                    + ": resume reads task identity from the branch");
        }
    }

    private static void requireGitOnlyAbsentWithInPlace(boolean present, String flagName) {
        if (present) {
            throw new UsageException(flagName + " cannot be combined with --mode=in-place: it is a git-only flag");
        }
    }
}
