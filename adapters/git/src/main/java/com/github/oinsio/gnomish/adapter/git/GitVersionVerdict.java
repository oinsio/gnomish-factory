package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.gittransfer.GitVersion;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The git floor verdict {@link GitVersionCheck#assess()} returns: the installed git meets
 * {@link GitVersion#FLOOR}, is below it, or reported no version at all. It refuses nothing and logs
 * nothing; the refusing form {@link GitVersionCheck#verify()} is built on it, so the startup path and
 * any non-refusing reader (the {@code doctor} check) cannot disagree about the same git (design D1
 * of add-doctor-command). The floor and its reason are the leaf's constants, {@link GitVersion#FLOOR}
 * and {@link GitVersion#FLOOR_REASON}, so a reader rendering a fix names them from there.
 *
 * <p>Implements FR4, FR7 of add-doctor-command.
 */
public sealed interface GitVersionVerdict {

    /**
     * Whether this verdict is a failure — the one question the refusing form and a reader mapping
     * it to ok/fail both ask.
     *
     * @return true when the installed git does not meet the floor or reported no version
     */
    boolean fails();

    /**
     * The installed git meets the floor.
     *
     * @param installed the version it reported
     */
    record Meets(GitVersion installed) implements GitVersionVerdict {
        @Override
        public boolean fails() {
            return false;
        }
    }

    /**
     * The installed git reported a version below the floor.
     *
     * @param installed the version it reported
     */
    record Below(GitVersion installed) implements GitVersionVerdict {
        @Override
        public boolean fails() {
            return true;
        }
    }

    /**
     * The installed git reported no version: a non-zero exit, or output no version line parses from.
     *
     * @param printed what {@code git --version} printed instead — its stdout on exit 0, its stderr
     *     otherwise — as captured
     */
    record Unreported(UntrustedText printed) implements GitVersionVerdict {
        @Override
        public boolean fails() {
            return true;
        }
    }
}
