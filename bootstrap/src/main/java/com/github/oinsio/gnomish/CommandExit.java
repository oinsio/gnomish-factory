package com.github.oinsio.gnomish;

import com.github.oinsio.gnomish.app.OrderedExit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The shared exit path every command runs through (design D6 of harden-logging-observability):
 * work, then context close, then logging stop.
 *
 * <p>Spring's own shutdown hook is switched off here. It is registered by {@link SpringApplication}
 * before the runners start and fires on its own thread, which on a {@code serve} stop means the
 * context closes — and, with it, {@link LoggingApplicationListener}'s logging-system teardown —
 * while slots are still draining and still writing the terminal lines that describe how the run
 * ended. Ordering those two against a drain is not expressible through the framework's hook (its
 * handlers run only <em>after</em> the context close, which is the wrong side of the drain), so
 * the factory owns the sequence outright, through {@link OrderedExit}.
 *
 * <p>Disabling the framework hook would otherwise cost every command its signal coverage: a
 * Ctrl-C during {@code gnomish run} used to close the context on the way out, and without a
 * replacement the tail of the log file would stay in the asynchronous appender's queue. So a
 * generic hook is registered here that runs the same sequence — and stands down for {@code serve},
 * which reserves the ownership before it registers a hook of its own (see {@code
 * ServeShutdownWiring}). Two hooks run concurrently in the JVM; only one may close the context.
 *
 * <p>The context is captured by an initializer rather than from {@link SpringApplication#run}'s
 * return value, because the sequence has to be installed <em>before</em> the runners start: on the
 * forever path {@code serve} blocks inside its runner until a signal arrives, so {@code run} has
 * not returned by the time the shutdown hook needs the context to close.
 *
 * <p>Implements FR9, NFR-R1 of harden-logging-observability.
 */
@NullMarked
final class CommandExit {

    /** The generic signal hook's thread name — {@code serve} registers its own, named separately. */
    static final String SIGNAL_HOOK_THREAD_NAME = "gnomish-exit";

    private CommandExit() {}

    /**
     * Installs the ordered exit around {@code application} and runs it to completion — for
     * {@code run} / {@code take} / {@code dashboard} that is the whole command; for {@code serve}
     * it returns once the feed thread has stopped.
     *
     * <p>{@code @DoNotMutate} for the "out-of-process delegation" reason of
     * `.claude/rules/testing.md`: the body is one hand-off into the seamed overload below with the
     * two production arguments — no decision, no branch, nothing computed here. Those arguments are
     * what makes it unassertable in process: it registers a real JVM shutdown hook (which a spec
     * cannot take back — the registry hands out no handle) and stops the real logging system the
     * remaining specs of the same JVM write through, which is exactly why the seamed overload
     * exists. The line is exercised end to end by the {@code com.github.oinsio.gnomish.e2e.*}
     * suites, which spawn the packaged jar and reach it through {@link FactoryApplication#main};
     * everything it wires — the install order, the hook body, the deferral to the serve hook — is
     * mutation-covered through the seamed overload by {@code CommandExitSpec}.
     *
     * @param application the application to run, not yet started
     * @param args the raw command-line arguments
     */
    @DoNotMutate
    static void start(SpringApplication application, String[] args) {
        start(application, args, CommandExit::stopLogging, Runtime.getRuntime()::addShutdownHook);
    }

    /**
     * Same as {@link #start(SpringApplication, String[])}, with the logging-system teardown and the
     * JVM hook registration seamed so a spec can assert the installed order without stopping the
     * logging system its own remaining specs write through, and without leaving real hooks behind.
     */
    static void start(
            SpringApplication application, String[] args, Runnable stopLogging, Consumer<Thread> hookRegistrar) {
        AtomicReference<ConfigurableApplicationContext> contextRef = new AtomicReference<>();
        application.setRegisterShutdownHook(false);
        application.addInitializers(contextRef::set);
        OrderedExit.install(() -> close(contextRef.get()), stopLogging);
        hookRegistrar.accept(new Thread(OrderedExit::onSignal, SIGNAL_HOOK_THREAD_NAME));
        application.run(args);
    }

    /**
     * Closes the context and stops logging — unless the shutdown hook has already taken ownership
     * of the sequence, in which case this defers to it (see {@link OrderedExit#onNormalExit()}).
     */
    static void finish() {
        OrderedExit.onNormalExit();
    }

    /** A run that failed before refresh leaves nothing to close; the exit path is still valid. */
    private static void close(@Nullable ConfigurableApplicationContext context) {
        if (context != null) {
            context.close();
        }
    }

    /**
     * Stops the logging system, flushing the asynchronous FILE appender. Resolved at call time,
     * not at install time: the system is bound during {@code run}, and a handler captured before
     * that would be the bootstrap one. A {@code null} handler means the active system has no
     * teardown of its own — nothing to flush, nothing to do.
     */
    static void stopLogging() {
        Runnable handler = LoggingSystem.get(CommandExit.class.getClassLoader()).getShutdownHandler();
        if (handler != null) {
            handler.run();
        }
    }
}
