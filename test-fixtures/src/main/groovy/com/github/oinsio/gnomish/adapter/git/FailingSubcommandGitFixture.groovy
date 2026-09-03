package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path

/**
 * Reusable Spock fixture: a stand-in {@code git} binary that answers every invocation by
 * delegating to the real one, except for a single named subcommand, which exits non-zero with
 * an empty stdout and a {@code fatal:} line on stderr.
 *
 * <p>This is the shape the exit-code verification rule (FR13 of harden-logging-observability)
 * needs and no real repository can be asked to produce on demand: a git probe that <em>ran</em>
 * and <em>failed</em>, so its empty output stream would read as a fact — an untouched state
 * directory, an unmoved tip, an empty branch listing — unless the caller checks the exit code.
 *
 * <p>Sibling of {@link StallingReadGitFixture}, which drives the other half of the same rule:
 * a probe that never ran to its own exit at all.
 */
trait FailingSubcommandGitFixture {

    /** The stderr the stand-in emits, and the evidence a caller is expected to carry through. */
    static final String GIT_FAILURE_STDERR = 'fatal: bad revision'

    /**
     * The marker whose appearance heals {@code subcommand} again, for specs asserting what an
     * operator is told when a failing probe starts answering — see {@link #healGit}.
     */
    Path healthMarker(Path dir, String subcommand) {
        dir.resolve("failing-git-${subcommand}.healed")
    }

    /** Ends the outage: from here on the stand-in delegates {@code subcommand} to the real git. */
    void healGit(Path dir, String subcommand) {
        healthMarker(dir, subcommand).toFile().text = ''
    }

    /**
     * Writes a stand-in {@code git} into {@code dir} that fails only on {@code subcommand}, and
     * only until {@link #healGit} is called for it.
     *
     * @param dir where the stand-in binary is written
     * @param subcommand the git subcommand to fail, matched after any leading {@code -c} pairs
     * @return the stand-in binary's path, for {@code new GitProcessRunner(path.toString())}
     */
    Path gitFailingOn(Path dir, String subcommand) {
        Path fakeGit = dir.resolve("failing-git-${subcommand}")
        fakeGit.toFile().text = """#!/bin/sh
subcommand_of() {
  while [ \$# -gt 0 ]; do
    case "\$1" in
      -c) shift 2 ;;
      *) echo "\$1"; return ;;
    esac
  done
}
if [ "\$(subcommand_of "\$@")" = "${subcommand}" ] && [ ! -f "${healthMarker(dir, subcommand)}" ]; then
  echo "${GIT_FAILURE_STDERR}" >&2
  exit 128
fi
exec git "\$@"
"""
        fakeGit.toFile().executable = true
        return fakeGit
    }
}
