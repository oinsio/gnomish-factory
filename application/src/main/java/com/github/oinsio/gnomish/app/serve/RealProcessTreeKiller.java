package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.subprocess.ProcessSupervisor;
import java.time.Duration;

/**
 * The real {@link ProcessTreeKiller} (FR11, design D9 of add-factory-serve): destroys every
 * descendant of the current JVM process, so no gnome subprocess survives the daemon on any exit
 * path. {@code ProcessHandle} has no notion of a process GROUP; walking descendants is this
 * design's cross-platform approximation of a process-group kill (design D9).
 *
 * <p>The discipline itself — cooperative signal first, forcible only for what is still alive after
 * the kill grace, a re-snapshot that catches a child forked inside that grace, and a reap so the
 * method does not return while something it signalled is still dying — is the shared {@link
 * ProcessSupervisor}'s (FR14, design D14 of bound-subprocess-commands). This class had a private
 * copy of the first two phases and neither of the last two: a descendant forked during the wait
 * survived the daemon, and the daemon could exit while its children were still going down. What is
 * left here is the one decision this class owns: whose descendants, and how long the grace is.
 *
 * <p>Thin and deliberately untested at the unit level — spawning and killing real OS processes in
 * a Spock spec would be heavy and flaky for no coverage benefit; the sequencing that decides
 * WHETHER and WHEN this runs is fully covered by {@code ServeShutdownSpec} against a fake {@link
 * ProcessTreeKiller}, and the kill itself by {@code ProcessSupervisorDescendantKillSpec} against
 * real processes in the module that owns it. Excluded from the PIT mutation gate (see
 * build.gradle's {@code excludedClasses}) as this project's documented integration-boundary
 * exception, the same category as {@code FactoryApplication}'s {@code main()} wiring.
 *
 * <p>Implements FR11, D9 of add-factory-serve; FR14, D14 of bound-subprocess-commands.
 */
public final class RealProcessTreeKiller implements ProcessTreeKiller {

    private static final Duration DEFAULT_FORCIBLE_WAIT = Duration.ofSeconds(1);

    private final ProcessSupervisor supervisor;

    public RealProcessTreeKiller() {
        this(DEFAULT_FORCIBLE_WAIT);
    }

    /** @param forcibleWait how long to give a destroyed descendant to exit before forcing it */
    RealProcessTreeKiller(Duration forcibleWait) {
        this.supervisor = new ProcessSupervisor(forcibleWait);
    }

    @Override
    public void killDescendants() {
        supervisor.terminateDescendants(ProcessHandle.current());
    }
}
