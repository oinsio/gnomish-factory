package com.github.oinsio.gnomish.app

import org.springframework.boot.DefaultApplicationArguments

/**
 * Shared factory for the one-line {@link DefaultApplicationArguments}
 * construction that fifteen app-layer spec files used to inline verbatim as a
 * private {@code args(String...)} helper. A plain Groovy trait, composable
 * alongside {@link AppAssemblyFixture} and {@link
 * com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture}; the pure
 * argument-parser specs implement only this one.
 */
trait ApplicationArgumentsFixture {

    /**
     * Wraps raw CLI tokens in a {@link DefaultApplicationArguments}, exactly as
     * Spring Boot hands them to the command layer.
     */
    static DefaultApplicationArguments args(String... raw) {
        new DefaultApplicationArguments(raw)
    }
}
