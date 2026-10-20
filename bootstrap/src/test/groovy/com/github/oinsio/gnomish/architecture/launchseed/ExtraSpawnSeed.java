package com.github.oinsio.gnomish.architecture.launchseed;

import java.util.List;

/**
 * A third spawn site: a class outside the allowlist of {@code GitLaunchBoundarySpec} that builds a
 * git process (FR7, design D7 of own-git-invocation-policy). The builder sits in a nested class only,
 * so the gate must map {@code ExtraSpawnSeed$Spawner} to this top-level class to name it.
 */
public final class ExtraSpawnSeed {

    private ExtraSpawnSeed() {}

    /**
     * The seeded spawn the gate exists to refuse; this class itself names no builder.
     *
     * @return a task that builds a builder for {@code git status} and never starts it
     */
    public static Runnable status() {
        return new Spawner();
    }

    private static final class Spawner implements Runnable {

        @Override
        public void run() {
            new ProcessBuilder(List.of("git", "status")).redirectErrorStream(true);
        }
    }
}
