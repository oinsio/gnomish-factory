package com.github.oinsio.gnomish.app.serve;

import java.time.Duration;
import java.util.List;

/**
 * The real {@link ProcessTreeKiller} (FR11, design D9): destroys every descendant of the current
 * JVM process — cooperative {@link ProcessHandle#destroy()} first, then {@link
 * ProcessHandle#destroyForcibly()} for anything still alive after a short wait — so no gnome
 * subprocess survives the daemon on any exit path. {@code ProcessHandle} has no notion of a
 * process GROUP; walking descendants is this design's cross-platform approximation of a
 * process-group kill (design D9).
 *
 * <p>Thin and deliberately untested at the unit level — spawning and killing real OS processes in
 * a Spock spec would be heavy and flaky for no coverage benefit; the sequencing that decides
 * WHETHER and WHEN this runs is fully covered by {@code ServeShutdownSpec} against a fake {@link
 * ProcessTreeKiller}. Excluded from the PIT mutation gate (see build.gradle's {@code
 * excludedClasses}) as this project's documented integration-boundary exception, the same
 * category as {@code FactoryApplication}'s {@code main()} wiring.
 *
 * <p>Implements FR11, D9 of add-factory-serve.
 */
public final class RealProcessTreeKiller implements ProcessTreeKiller {

    private static final Duration DEFAULT_FORCIBLE_WAIT = Duration.ofSeconds(1);

    private final Duration forcibleWait;

    public RealProcessTreeKiller() {
        this(DEFAULT_FORCIBLE_WAIT);
    }

    /** @param forcibleWait how long to give a destroyed descendant to exit before forcing it */
    RealProcessTreeKiller(Duration forcibleWait) {
        this.forcibleWait = forcibleWait;
    }

    @Override
    public void killDescendants() {
        List<ProcessHandle> descendants = ProcessHandle.current().descendants().toList();
        if (descendants.isEmpty()) {
            return;
        }
        descendants.forEach(ProcessHandle::destroy);
        sleepQuietly();
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(forcibleWait.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
