package com.github.oinsio.gnomish.app.conformance;

import com.github.oinsio.gnomish.adapter.git.RepositoryInit;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.subprocess.ProcessLaunch;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/**
 * What the conformance kit runs a {@code program} candidate with (FR12 of define-executor-contract,
 * design D19): the time every bounded wait reads and the poll wait sleeps on, the child-environment
 * allowlist each case's host environment composes from, the launch owner its processes start
 * through, the git initialisation of each case's working copy, and where each run's stderr file
 * goes. The program's {@link com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment}
 * is built from it per case, with the factory's own bounds.
 *
 * <p>Implements FR12 of define-executor-contract.
 *
 * @param time the clock and the sleeper; never null
 * @param allowlist the child-environment allowlist; never null
 * @param launch the launch owner of every child; never null
 * @param repositories the git initialisation of a case's working copy; never null
 * @param stderrParent the directory each run's stderr directory is created in; never null
 */
@NullMarked
public record KitEquipment(
        TimeEquipment time,
        ChildEnvAllowlist allowlist,
        ProcessLaunch launch,
        RepositoryInit repositories,
        Path stderrParent) {

    /** Refuses a missing member. */
    public KitEquipment {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(allowlist, "allowlist");
        Objects.requireNonNull(launch, "launch");
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(stderrParent, "stderrParent");
    }
}
