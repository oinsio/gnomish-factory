package com.github.oinsio.gnomish.domain.engine.port;

/**
 * An opaque handle to the working copy a stage runs against. The engine never
 * inspects it — it only threads the handle from the caller through to the
 * executor and check runners, which alone know its concrete shape (an
 * {@code agent-cli} checkout on disk, an {@code api} context, ...). Keeping it
 * opaque is deliberate: the domain touches no filesystem (design D1), so a
 * marker interface is all the boundary needs.
 *
 * <p>Adapters supply a real implementation; tests use a trivial one
 * ({@code new Workspace() {}}). The interface declares no members precisely
 * because there is nothing the engine is allowed to ask of a workspace.
 *
 * <p>Implements FR1 of add-stage-engine.
 */
public interface Workspace {}
