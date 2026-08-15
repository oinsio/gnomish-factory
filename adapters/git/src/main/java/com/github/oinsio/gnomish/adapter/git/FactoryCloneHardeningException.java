package com.github.oinsio.gnomish.adapter.git;

import java.io.Serial;

/**
 * Thrown when {@link FactoryCloneHardening} cannot point a factory-managed clone's {@code
 * core.hooksPath} at an empty directory. Hardening failure is fatal, not best-effort: an un-hardened
 * clone leaves a gnome-installed hook able to fire in a factory-owned filesystem namespace — the
 * exact hole FR17/design D11 closes — so the run must abort rather than proceed unprotected.
 *
 * <p>Implements FR17 of add-sandbox-core.
 */
public final class FactoryCloneHardeningException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param cloneDir the clone whose hooks could not be neutralized
     * @param detail the failing command's captured stderr, or the underlying exception's message
     */
    public FactoryCloneHardeningException(String cloneDir, String detail) {
        super("failed to harden factory clone \"" + cloneDir + "\" (core.hooksPath): " + detail);
    }

    /**
     * @param cloneDir the clone whose hooks could not be neutralized
     * @param cause the underlying exception
     */
    public FactoryCloneHardeningException(String cloneDir, Throwable cause) {
        super("failed to harden factory clone \"" + cloneDir + "\" (core.hooksPath)", cause);
    }
}
