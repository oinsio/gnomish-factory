package com.github.oinsio.gnomish.adapter.git;

/**
 * Thrown when the configured {@code git} executable could not even be launched (binary missing
 * from {@code PATH}, not executable, ...) — distinct from a git command that ran and exited
 * non-zero, which {@link GitProcessRunner#run} reports via {@link GitCommandResult#exitCode()}
 * instead of throwing.
 *
 * <p>Implements FR2 of add-git-workflow.
 */
final class GitBinaryNotFoundException extends RuntimeException {

    GitBinaryNotFoundException(String gitBinary, Throwable cause) {
        super("git binary not found or not executable: '" + gitBinary + "'", cause);
    }
}
