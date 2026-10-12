package com.github.oinsio.gnomish.sandbox;

import java.util.Optional;

/**
 * Where a {@link ChildEnvAllowlist} resolves the value of a variable it admits, by name: the base
 * set, the operator passthrough, and the factory-set variables an agent round asks for. A role
 * interface of this module (a seam a spec fakes is a type the owning module owns, never a JDK
 * functional type): the port layer cannot reach the secrets port, so the composition root binds
 * this role to {@code SecretsProvider::find}, and every value a child environment carries is
 * resolved through that one port — the env/file adapter stays the only reader of the process
 * environment (NFR-S3, finding F107 of tighten-spec-contract).
 *
 * <p>Implements NFR-S3 of tighten-spec-contract.
 */
@FunctionalInterface
public interface ChildEnvValues {

    /** Resolves nothing: the Null Object behind {@link ChildEnvAllowlist#none()}. */
    ChildEnvValues NONE = name -> Optional.empty();

    /**
     * Resolves the variable named {@code name}.
     *
     * @param name the variable's exact name; never null
     * @return its non-blank value, or empty when it is absent or blank; never null
     */
    Optional<String> find(String name);
}
