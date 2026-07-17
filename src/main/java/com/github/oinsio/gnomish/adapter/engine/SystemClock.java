package com.github.oinsio.gnomish.adapter.engine;

import com.github.oinsio.gnomish.domain.engine.port.Clock;
import java.time.Instant;

/**
 * The production {@link Clock}: wraps {@link java.time.Clock#systemUTC()}, following
 * the same {@code java.time.Clock}-wrapping idiom used elsewhere in the codebase
 * (e.g. {@code AdHocTaskSynthesizer}'s injected clock).
 *
 * <p>Implements D10, M2 of add-manual-run.
 */
public final class SystemClock implements Clock {

    private final java.time.Clock delegate;

    /** Wraps {@link java.time.Clock#systemUTC()}. */
    public SystemClock() {
        this(java.time.Clock.systemUTC());
    }

    /**
     * Wraps an explicit {@link java.time.Clock}, e.g. for a fixed clock in tests
     * outside the engine's own port-contract seam.
     *
     * @param delegate the clock to read {@link #now()} from; never null
     */
    public SystemClock(java.time.Clock delegate) {
        this.delegate = delegate;
    }

    @Override
    public Instant now() {
        return delegate.instant();
    }
}
