package com.github.oinsio.gnomish.adapter.git;

/**
 * The outcome of one {@code git} subprocess invocation: exit code and stdout/stderr captured as
 * separate streams (unlike {@code CommandProcessRunner}'s merged-stream approach for shell
 * checks) so callers can parse git plumbing output cleanly while still seeing warnings on
 * stderr.
 *
 * <p>A non-zero {@link #exitCode()} is a normal, expected outcome here — callers (branch
 * creation, commit, push, ...) decide per-command what a given exit code means. This type never
 * represents "the git binary could not be launched"; that case is a thrown {@link
 * GitBinaryNotFoundException} instead.
 *
 * <p>Implements FR2 of add-git-workflow.
 *
 * @param exitCode the git process's exit code
 * @param stdout the process's standard output, captured in full
 * @param stderr the process's standard error, captured in full
 */
record GitCommandResult(int exitCode, String stdout, String stderr) {}
