package com.github.oinsio.gnomish.app

/**
 * Reusable Spock fixture: captures whatever a CLI command writes to {@code System.out} while it
 * runs, so specs can assert on rendered text/JSON output without touching the real console.
 * Factored out of {@code StatusCommandSpec}/{@code UsageCommandSpec} and their sibling specs,
 * which each repeated this identical capture-and-restore logic.
 */
trait StdoutCaptureFixture {

    /** Runs {@code action} with {@code System.out} redirected, returning whatever it printed. */
    static String captureStdout(Closure action) {
        def originalOut = System.out
        def out = new ByteArrayOutputStream()
        System.out = new PrintStream(out, true, 'UTF-8')
        try {
            action.call()
        } finally {
            System.out = originalOut
        }
        return out.toString('UTF-8')
    }

    /**
     * Like {@link #captureStdout}, but for actions expected to throw: runs {@code action} with
     * stdout captured, swallows exactly {@code thrownType} (asserting it was thrown) and returns
     * whatever reached stdout before the throw, so callers can assert on both in one block.
     */
    static String captureStdoutExpectingThrow(Class<? extends Throwable> thrownType, Closure action) {
        def originalOut = System.out
        def out = new ByteArrayOutputStream()
        System.out = new PrintStream(out, true, 'UTF-8')
        try {
            action.call()
            throw new AssertionError("expected ${thrownType.simpleName} to be thrown, but action completed normally")
        } catch (AssertionError rethrow) {
            throw rethrow
        } catch (Throwable t) {
            if (!thrownType.isInstance(t)) {
                throw t
            }
        } finally {
            System.out = originalOut
        }
        return out.toString('UTF-8')
    }
}
