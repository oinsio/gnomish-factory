package com.github.oinsio.gnomish.adapter.plugin

import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory

/**
 * The github plugin artifact as it sits on the running classpath, and a view of that classpath with
 * it taken away — the two things a spec needs to talk about github's packaging without assuming how
 * the build laid it out (FR12, M2 of add-plugin-architecture).
 *
 * <p>Where a provider class came from is read from its own {@code CodeSource}, so this works for
 * both shapes the artifact takes: a jar in a packaged distribution, and a module output tree during
 * a build. In the second shape classes and resources are sibling output directories, so the shared
 * prefix — not the classes directory itself — is what identifies the artifact.
 *
 * <p>{@link #hiddenFrom} is how "removing the github jar" is staged inside one JVM: a loader that
 * serves every service registration on the classpath except the ones this artifact contributes.
 * Discovery reads registrations and nothing else, so a provider whose registration is invisible is
 * exactly as absent as one whose jar was never installed.
 */
class GithubArtifact {

    /** Gradle's per-module output root; classes/ and resources/ are siblings underneath it. */
    private static final String BUILD_DIR = '/build/'

    /** The prefix every resource of the github artifact shares: the jar itself, or its output tree. */
    static String root() {
        def location = GithubTrackerAdapterFactory.protectionDomain.codeSource.location.toString()
        int build = location.indexOf(BUILD_DIR)
        build < 0 ? location : location.substring(0, build + BUILD_DIR.length())
    }

    /** Whether a classpath resource is one the github artifact contributed. */
    static boolean contributes(URL resource) {
        resource.toString().contains(root())
    }

    /** The same classpath with the github artifact's service registrations taken away. */
    static ClassLoader hiddenFrom(ClassLoader parent) {
        new GithublessClassLoader(parent)
    }
}

/** Delegates everything to its parent except the github artifact's own resources. */
class GithublessClassLoader extends ClassLoader {

    GithublessClassLoader(ClassLoader parent) {
        super(parent)
    }

    @Override
    Enumeration<URL> getResources(String name) {
        Collections.enumeration(super.getResources(name).toList().findAll {
            !GithubArtifact.contributes(it)
        })
    }
}
