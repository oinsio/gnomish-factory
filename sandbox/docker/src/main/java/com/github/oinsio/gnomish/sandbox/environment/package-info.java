/**
 * The execution-environment backends behind the {@code
 * TaskExecutionEnvironment} port (design D1, D8, D20): the host adapter and the
 * docker-CLI container adapter through which the factory owns a task's
 * working-copy lifecycle, runs every gnome-product process, and moves
 * factory-authored files to and from the environment.
 *
 * <p>The port itself, its value model ({@code ExecCommand}, {@code ExecHandle},
 * {@code ProcessStartException}), the capability-passport negotiation and the
 * typed sandbox configuration live one layer down in {@link
 * com.github.oinsio.gnomish.sandbox} — the backend-free port module {@code
 * :sandbox:core} (FR8, design D7/D11 of split-into-modules). This package holds
 * only backend mechanics, plus {@link
 * com.github.oinsio.gnomish.sandbox.environment.PathEscapeException}, the
 * path-escape signal of the host channel-path check.
 *
 * <p>Host adapter (task 1.3, FR2): {@link
 * com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment}
 * over worktree + {@link java.lang.ProcessBuilder} mechanics, with {@link
 * com.github.oinsio.gnomish.sandbox.environment.HostExecHandle} as its process
 * handle and {@link
 * com.github.oinsio.gnomish.sandbox.environment.ChannelPathResolver} enforcing
 * that channel paths stay under the working copy or scratch roots (symlinks
 * included).
 *
 * <p>Container adapter (task group 4, FR3, FR4, FR10, FR11, NFR-R1, NFR-R2):
 * {@link
 * com.github.oinsio.gnomish.sandbox.environment.ContainerTaskExecutionEnvironment}
 * — one network, one working-copy volume, and one keep-alive container per
 * task, all managed through {@link
 * com.github.oinsio.gnomish.sandbox.environment.DockerCli} ({@code docker} as a
 * subprocess, like git) with argv assembled by {@link
 * com.github.oinsio.gnomish.sandbox.environment.DockerCommands} and {@link
 * com.github.oinsio.gnomish.sandbox.environment.GuardCommands}, labelled by
 * {@link com.github.oinsio.gnomish.sandbox.environment.FactoryDockerLabels} for
 * disposal and the {@link
 * com.github.oinsio.gnomish.sandbox.environment.ContainerOrphanSweeper}; {@link
 * com.github.oinsio.gnomish.sandbox.environment.ContainerFileChannel} and {@link
 * com.github.oinsio.gnomish.sandbox.environment.ContainerHarvest} are its file
 * and harvest seams; {@link
 * com.github.oinsio.gnomish.sandbox.environment.ContainerEnvironmentDisposal}
 * and {@link
 * com.github.oinsio.gnomish.sandbox.environment.ContainerEnvironmentReaper}
 * (factory-serve delta) realize keep-then-dispose end-of-life; {@link
 * com.github.oinsio.gnomish.sandbox.environment.DockerOutput} and {@link
 * com.github.oinsio.gnomish.sandbox.environment.DockerResult} parse subprocess
 * output, and {@link
 * com.github.oinsio.gnomish.sandbox.environment.DockerUnavailableException}
 * signals the runtime-outage infrastructure failure.
 *
 * <p>Child-process environment and stdin (task group 7, FR9, D6): {@link
 * com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist} composes the
 * layered positive allowlist (adapter base &cup; operator passthrough &cup;
 * factory-set protocol vars) every {@code exec()} child receives, with
 * credential names refused at construction; {@link
 * com.github.oinsio.gnomish.sandbox.environment.ChildProcessStdin} is the
 * shared fire-and-forget stdin delivery path for both adapters.
 *
 * <p>Segment lease and self-checked construction (sandbox integration pass,
 * task 9.2, FR8, FR12, FR13, NFR-P1, D5): {@link
 * com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease} and {@link
 * com.github.oinsio.gnomish.sandbox.environment.LeasedEnvironment} reuse one
 * materialized environment across a segment's stages, lazily materializing on
 * first use and crossing segment boundaries via harvest &rarr; dispose &rarr;
 * materialize; {@link
 * com.github.oinsio.gnomish.sandbox.environment.SelfCheckedEnvironment}
 * decorates any adapter so materialize always runs the mandatory self-check
 * before returning; {@link
 * com.github.oinsio.gnomish.sandbox.environment.ContainerEnvironments} is the
 * per-task construction seam assembling a container environment with its guard
 * and self-check into one.
 *
 * <p>Segment planning (task group 3, FR12–FR14) lives one layer down with the
 * binding and need vocabulary it plans over — {@code AdapterBinding},
 * {@code BindingResolver}, {@code SandboxNeed}, {@code SandboxReconciler},
 * {@link com.github.oinsio.gnomish.sandbox.Segment} and {@link
 * com.github.oinsio.gnomish.sandbox.SegmentPlanner} — grouping contiguous
 * same-binding stages into one environment's lifespan, split by a binding
 * change or {@code requires-fresh}; {@code EnvironmentLease} here consumes the
 * plan.
 *
 * <p>Egress guard and self-check (task group 6, FR7, FR8): {@link
 * com.github.oinsio.gnomish.sandbox.environment.EgressGuard} — the per-task
 * mitmdump container (SNI/CONNECT mode, no TLS opening) on the internal task
 * network plus the bridge, converged to running with restart-on-outage
 * (NFR-R1), its allowlist rendered by {@code EgressGuardConfig} and its
 * structured denials parsed by {@code GuardDenialLog} into findings (NFR-O1);
 * {@link com.github.oinsio.gnomish.sandbox.environment.EnvironmentSelfCheck} —
 * the mandatory fail-closed probes before the first gnome-product process, with
 * {@link com.github.oinsio.gnomish.sandbox.environment.SelfCheckFailedException}
 * and {@link
 * com.github.oinsio.gnomish.sandbox.environment.GuardUnavailableException} as
 * the infrastructure-failure signals.
 *
 * <p>Implements FR1, FR2, FR3, FR4, FR7, FR8, FR9, FR10, FR11, FR12, FR13,
 * FR14, NFR-O1, NFR-P1, NFR-R1, NFR-R2, NFR-S3, G2 of add-sandbox-core.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.sandbox.environment;

import org.jspecify.annotations.NullMarked;
