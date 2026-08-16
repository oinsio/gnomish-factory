package com.github.oinsio.gnomish.build

import org.gradle.api.provider.SetProperty

/**
 * Declares, per module, the complete set of sibling projects its production classpaths may reach
 * (FR2, UX2 of split-into-modules). Everything else is forbidden, transitively — this is a
 * whitelist, so a new edge cannot appear by being inherited through an {@code api} dependency.
 *
 * <p>Read by the {@code layering-conventions} plugin's {@code verifyModuleLayering} task. The whole
 * layering direction is therefore stated once per module, next to that module's dependencies, and
 * a violation names both the offending edge and the allowed set.
 */
abstract class LayeringExtension {

    /**
     * Gradle project paths (e.g. {@code ':domain'}) this module's {@code compileClasspath} and
     * {@code runtimeClasspath} may reach, directly or transitively. Empty means "nothing internal".
     */
    abstract SetProperty<String> getAllowedProjects()
}
