package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.take.AbortFuse;
import java.nio.file.Path;
import java.util.List;

/**
 * The <em>slot wiring</em>: the equipment one take slot works with, fixed for as long as the slot
 * exists — once per {@code gnomish take} invocation, or once per {@code serve} daemon and shared
 * by all of its slots (design D1 of introduce-slot-wiring). It is the counterpart of the {@link
 * TakeOrder}: the order is what one invocation works <em>to</em> and stays a parameter; the wiring
 * is what it works <em>with</em> and becomes fields of the components that use it.
 *
 * <p>Built only where every member exists — after the tracker is provisioned and the run's
 * heartbeat and listener-augmented assembly are in hand — so exactly two assembly points build
 * one (design, "Where a {@code SlotWiring} is built"). Its two named sub-groups, {@link AbortFuse}
 * and {@link ClaimTenure}, are the pairs their consumers already take together. It carries no
 * claim epoch: {@link TaskGit#epochs()} is that book's single owner (design D5).
 *
 * <p>Never log a wiring whole: the record {@code toString} renders the credential variable names
 * it carries (NFR-S1). It carries names only, never a credential value.
 *
 * <p>Implements FR1 of introduce-slot-wiring.
 *
 * @param assembly the run assembly (listener-augmented with the heartbeat's progress listener)
 * @param git the task-git capability set, including the claim-epoch book
 * @param worktreesRoot the directory the slot's task worktrees live under
 * @param taskIdMdcKey the MDC key the task id is bound under while the slot works a task
 * @param abort the abort handler and its threshold K
 * @param credentialEnvVarsToScrub the declared credential variable names scrubbed from agent
 *     environments; names only
 * @param containerTakeSupport the container-mode seam of the take chain
 * @param tenure the claim beat and claim-loss flag of the run's heartbeat
 * @param trustedBase the trusted tier bound once at startup
 */
public record SlotWiring(
        RunAssembly assembly,
        TaskGit git,
        Path worktreesRoot,
        String taskIdMdcKey,
        AbortFuse abort,
        List<String> credentialEnvVarsToScrub,
        ContainerTakeSupport containerTakeSupport,
        ClaimTenure tenure,
        TrustedBaseContext trustedBase) {}
