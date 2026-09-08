package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.baseref.BaseDefinition;
import java.util.Objects;

/**
 * The trusted-tier base policy bound once at startup ({@link TrustedTierStartup.StartupLaw}) and
 * threaded, unchanged, to every claim this process ever makes (FR13, D15 of
 * add-base-ref-resolution): the project's allowed bases plus configured default, and the
 * repository default branch origin named at startup.
 *
 * <p>A parameter object rather than two loose values threaded through the take/serve wiring
 * (`.claude/rules/process-invariants.md`'s parameter-count rule): the two travel everywhere
 * together, read together once at startup and consulted together by every fresh claim's base
 * resolution.
 *
 * <p><b>Never re-read per claim.</b> A fresh claim's base resolution (task 6.2 of
 * add-base-ref-resolution) consults exactly these two values plus the task's own explicit/
 * designator input — never a fresh {@code discoverDefaultBranch} call and never a fresh
 * {@code task-branch.base} config read. That is what "bound once at startup" buys: the daemon
 * reads origin's default branch and the project's base policy exactly once per process lifetime,
 * not once per claim.
 *
 * <p>Implements FR13, D15 of add-base-ref-resolution.
 *
 * @param base the project's allowed bases and configured default, as {@link
 *     TrustedTierStartup.StartupLaw#base()} bound it
 * @param defaultBranch the repository default branch origin named at startup, as {@link
 *     TrustedTierStartup.StartupLaw#defaultBranch()} bound it
 */
public record TrustedBaseContext(BaseDefinition base, String defaultBranch) {

    /** Both halves of the trusted tier travel together; neither may be missing. */
    public TrustedBaseContext {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(defaultBranch, "defaultBranch");
    }
}
