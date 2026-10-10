package com.github.oinsio.gnomish.app;

/**
 * The container-dispatch collaborators every {@code take} entry point needs to route a fresh
 * claim through the container assembly instead of the host one (FR1 of
 * add-serve-sandbox-lifecycle): the execution-mode selector {@code TakeWorkRouter} asks for the
 * run's plan, and the {@code tracked}-labelling container support factory itself (as opposed to
 * {@code run}'s {@code manual}-labelling one — the two lambdas differ only in the {@code
 * OwnershipMode} they close over). Bundled as one object so the plumbing from {@code
 * ManualRunRunner} down through {@code take}/{@code serve}'s dispatch chain carries one parameter
 * instead of two.
 *
 * <p>The selector is the root's one instance (design D22 of
 * supervise-daemon-loops-and-embed-dashboard): this bundle declares no accessor for the bindings,
 * the sandbox config, the binding registry or the runtime probe, so no consumer can read them back
 * out and decide the execution mode by a second route. The support factory already holds the
 * installation's settings, so no property set travels to its {@code create} (FR20 of
 * make-checkpoint-gate-durable).
 *
 * <p>Public so {@code app.serve.TakeSlotRunner} — a different package — can forward one opaquely
 * from {@code serve}'s own wiring down into {@link TakeClaimAndWorkFactory#forSlot}, mirroring
 * why {@link TakeClaimAndWork} itself is public (see its class javadoc).
 *
 * <p>Implements FR1, FR2, FR8 of add-serve-sandbox-lifecycle; FR18 of
 * supervise-daemon-loops-and-embed-dashboard.
 *
 * @param modeSelector the root's execution-mode selector; never null
 * @param containerSupportFactory builds a run's container support, stamping {@code tracked};
 *     never null
 */
public record ContainerTakeSupport(SandboxModeSelector modeSelector, ContainerSupportFactory containerSupportFactory) {}
