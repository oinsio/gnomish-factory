/**
 * The {@code TaskExecutionEnvironment} port and its host adapter (design D1,
 * D8, D20): the single opaque seam through which the factory owns a task's
 * working-copy lifecycle, runs every gnome-product process, and moves
 * factory-authored files to and from the environment.
 *
 * <p>Port and value model: {@link
 * com.github.oinsio.gnomish.adapter.environment.TaskExecutionEnvironment} — the
 * port (materialize / exec / putFile / readFile / harvest / dispose, plus the
 * scratch root and the capability passport); {@link
 * com.github.oinsio.gnomish.adapter.environment.ExecCommand} and {@link
 * com.github.oinsio.gnomish.adapter.environment.ExecHandle} — the exec request
 * and the live process handle (streamed output, exit control); {@link
 * com.github.oinsio.gnomish.adapter.environment.CapabilityPassport} and {@link
 * com.github.oinsio.gnomish.adapter.environment.IsolationLevel} — the
 * machine-readable passport reconciled fail-closed against a stage's declared
 * needs; {@link
 * com.github.oinsio.gnomish.adapter.environment.ProcessStartException} and
 * {@link com.github.oinsio.gnomish.adapter.environment.PathEscapeException} —
 * the launch-failure and path-escape signals.
 *
 * <p>Host adapter (task 1.3, FR2): {@link
 * com.github.oinsio.gnomish.adapter.environment.HostTaskExecutionEnvironment}
 * over worktree + {@link java.lang.ProcessBuilder} mechanics, with {@link
 * com.github.oinsio.gnomish.adapter.environment.HostExecHandle} as its process
 * handle and {@link
 * com.github.oinsio.gnomish.adapter.environment.ChannelPathResolver} enforcing
 * that channel paths stay under the working copy or scratch roots (symlinks
 * included).
 *
 * <p>Container adapter (task group 4, FR3, FR4, FR10, FR11, NFR-R1, NFR-R2):
 * {@link
 * com.github.oinsio.gnomish.adapter.environment.ContainerTaskExecutionEnvironment}
 * — one network, one working-copy volume, and one keep-alive container per
 * task, all managed through {@link
 * com.github.oinsio.gnomish.adapter.environment.DockerCli} ({@code docker} as a
 * subprocess, like git) with argv assembled by {@link
 * com.github.oinsio.gnomish.adapter.environment.DockerCommands} and {@link
 * com.github.oinsio.gnomish.adapter.environment.GuardCommands}, labelled by
 * {@link com.github.oinsio.gnomish.adapter.environment.FactoryDockerLabels} for
 * disposal and the {@link
 * com.github.oinsio.gnomish.adapter.environment.ContainerOrphanSweeper}; {@link
 * com.github.oinsio.gnomish.adapter.environment.ContainerFileChannel} and {@link
 * com.github.oinsio.gnomish.adapter.environment.ContainerHarvest} are its file
 * and harvest seams; {@link
 * com.github.oinsio.gnomish.adapter.environment.ContainerEnvironmentDisposal}
 * and {@link
 * com.github.oinsio.gnomish.adapter.environment.ContainerEnvironmentReaper}
 * (factory-serve delta) realize keep-then-dispose end-of-life; {@link
 * com.github.oinsio.gnomish.adapter.environment.DockerOutput} and {@link
 * com.github.oinsio.gnomish.adapter.environment.DockerResult} parse subprocess
 * output, and {@link
 * com.github.oinsio.gnomish.adapter.environment.DockerUnavailableException}
 * signals the runtime-outage infrastructure failure.
 *
 * <p>Child-process environment and stdin (task group 7, FR9, D6): {@link
 * com.github.oinsio.gnomish.adapter.environment.ChildEnvAllowlist} composes the
 * layered positive allowlist (adapter base &cup; operator passthrough &cup;
 * factory-set protocol vars) every {@code exec()} child receives, with
 * credential names refused at construction; {@link
 * com.github.oinsio.gnomish.adapter.environment.ChildProcessStdin} is the
 * shared fire-and-forget stdin delivery path for both adapters.
 *
 * <p>Segment lease and self-checked construction (sandbox integration pass,
 * task 9.2, FR8, FR12, FR13, NFR-P1, D5): {@link
 * com.github.oinsio.gnomish.adapter.environment.EnvironmentLease} and {@link
 * com.github.oinsio.gnomish.adapter.environment.LeasedEnvironment} reuse one
 * materialized environment across a segment's stages, lazily materializing on
 * first use and crossing segment boundaries via harvest &rarr; dispose &rarr;
 * materialize; {@link
 * com.github.oinsio.gnomish.adapter.environment.SelfCheckedEnvironment}
 * decorates any adapter so materialize always runs the mandatory self-check
 * before returning; {@link
 * com.github.oinsio.gnomish.adapter.environment.ContainerEnvironments} is the
 * per-task construction seam assembling a container environment with its guard
 * and self-check into one.
 *
 * <p>Binding resolution and reconciliation (task group 3, FR12–FR14): {@link
 * com.github.oinsio.gnomish.adapter.environment.AdapterBinding} — the operator's
 * host/container choice and its fixed passport; {@link
 * com.github.oinsio.gnomish.adapter.environment.BindingResolver} — resolves each
 * stage to a binding from {@code factory.bindings.*}, container by default with
 * no silent host fallback (D13); {@link
 * com.github.oinsio.gnomish.adapter.environment.SandboxNeed} and {@link
 * com.github.oinsio.gnomish.adapter.environment.SandboxReconciler} — reconcile a
 * stage's declared needs against the bound passport, fail-closed on any unmet
 * one; {@link com.github.oinsio.gnomish.adapter.environment.Segment} and {@link
 * com.github.oinsio.gnomish.adapter.environment.SegmentPlanner} — group
 * contiguous same-binding stages into one environment's lifespan, split by a
 * binding change or {@code requires-fresh}.
 *
 * <p>Egress guard and self-check (task group 6, FR7, FR8): {@link
 * com.github.oinsio.gnomish.adapter.environment.EgressGuard} — the per-task
 * mitmdump container (SNI/CONNECT mode, no TLS opening) on the internal task
 * network plus the bridge, converged to running with restart-on-outage
 * (NFR-R1), its allowlist rendered by {@code EgressGuardConfig} and its
 * structured denials parsed by {@code GuardDenialLog} into findings (NFR-O1);
 * {@link com.github.oinsio.gnomish.adapter.environment.EnvironmentSelfCheck} —
 * the mandatory fail-closed probes before the first gnome-product process, with
 * {@link com.github.oinsio.gnomish.adapter.environment.SelfCheckFailedException}
 * and {@link
 * com.github.oinsio.gnomish.adapter.environment.GuardUnavailableException} as
 * the infrastructure-failure signals.
 *
 * <p>Implements FR1, FR2, FR3, FR4, FR7, FR8, FR9, FR10, FR11, FR12, FR13,
 * FR14, NFR-O1, NFR-P1, NFR-R1, NFR-R2, NFR-S3, G2 of add-sandbox-core.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.environment;

import org.jspecify.annotations.NullMarked;
