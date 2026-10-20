package com.github.oinsio.gnomish.architecture.launchseed;

import java.util.ArrayList;
import java.util.List;

/**
 * A git launcher that skips the owner: it spells its own command line rather than taking it from
 * {@code GitInvocation.commandLine}, so the invocation policy never reaches the process it builds
 * — the shape {@code GitLaunchBoundarySpec}'s second detector exists to fail on (FR7, design D7 of
 * own-git-invocation-policy).
 */
public final class OwnerSkippingLauncherSeed {

    private OwnerSkippingLauncherSeed() {}

    /**
     * Builds a git process from a hand-made command line.
     *
     * @param gitBinary the git executable
     * @param args the git arguments
     * @return a builder carrying no invocation policy, never started
     */
    public static ProcessBuilder launch(String gitBinary, List<String> args) {
        List<String> line = new ArrayList<>();
        line.add(gitBinary);
        line.addAll(args);
        return new ProcessBuilder(line);
    }
}
