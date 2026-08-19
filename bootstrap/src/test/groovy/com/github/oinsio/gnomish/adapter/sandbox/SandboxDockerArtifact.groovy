package com.github.oinsio.gnomish.adapter.sandbox

import com.github.oinsio.gnomish.sandbox.environment.ContainerBindingProvider

/**
 * The {@code :sandbox:docker} backend artifact as it sits on the running classpath, and a view of
 * that classpath with it taken away — how "a distribution stripped of the container backend" is
 * staged inside one JVM (M3 of open-adapter-binding-registry).
 *
 * Mirrors {@code GithubArtifact}: the artifact root is read from a provider class's own {@code
 * CodeSource}, so it works for both shapes the module takes — a jar in a packaged distribution and
 * an output tree during a build, where classes and resources are sibling directories under one
 * build root. Discovery reads service registrations and nothing else, so a backend whose
 * registration is invisible is exactly as absent as one whose jar was never installed.
 */
class SandboxDockerArtifact {

    /** Gradle's per-module output root; classes/ and resources/ are siblings underneath it. */
    private static final String BUILD_DIR = '/build/'

    /** The prefix every resource of the docker backend shares: the jar itself, or its output tree. */
    static String root() {
        def location = ContainerBindingProvider.protectionDomain.codeSource.location.toString()
        int build = location.indexOf(BUILD_DIR)
        build < 0 ? location : location.substring(0, build + BUILD_DIR.length())
    }

    /** The same classpath with the docker backend's service registrations taken away. */
    static ClassLoader hiddenFrom(ClassLoader parent) {
        new DockerlessClassLoader(parent)
    }
}

/** Delegates everything to its parent except the docker backend's own resources. */
class DockerlessClassLoader extends ClassLoader {

    DockerlessClassLoader(ClassLoader parent) {
        super(parent)
    }

    @Override
    Enumeration<URL> getResources(String name) {
        Collections.enumeration(super.getResources(name).toList().findAll {
            !it.toString().contains(SandboxDockerArtifact.root())
        })
    }
}
