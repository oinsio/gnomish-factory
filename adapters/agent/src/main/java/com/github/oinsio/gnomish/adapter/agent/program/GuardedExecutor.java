package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one owner of the egress guard's liveness probe (design D13 of extract-agent-round-runner; D14
 * of tighten-spec-contract): a decorator over the contract's {@link Executor}, applied where an
 * executor is bound to an environment — the stage half ({@link ProgramStageExecutor}, over what its
 * {@link RoundExecutorFactory} binds to the open round's environment) and the check half ({@link
 * ProgramCheckBinding}, over the fresh box of one request). It probes the environment's guard before
 * each {@code start} and {@code poll} and again after each returns. A guard found dead, or a runtime
 * that cannot say, makes the request {@code failed/infrastructure} naming the guard, the probe's
 * message quoted as container text: before the request, no request is issued; after it, the
 * executor's answer is replaced, its usage and denials kept, since they were spent. The engine reads
 * that as a cannot-verify for a check and an infrastructure failure for a stage round, so no attempt
 * is burned. An environment with no guard (the host) answers the probe by doing nothing.
 *
 * <p>The decorator takes no {@code PendingAdoption} role and forwards none: the check round is the
 * one caller of an adoption (design D14 of migrate-verify-to-executor-contract), so the check binding
 * hands the role of the executor it built beside the decorator, in its {@code BoundCheck}. The
 * adoption is a Java-side table entry, never a request, so it needs no probe; the poll that follows
 * it is probed like any other.
 *
 * <p>The stage half asks {@link #foundGuardDead()} after its request to discard the round rather than
 * close it: a round whose guard died broke its cage, so nothing of it is committed (D14 of
 * tighten-spec-contract). One decorator serves one bound executor, used by one thread — the round's or
 * the check request's — so the observation is a plain field.
 *
 * <p>Implements FR15 of extract-agent-round-runner.
 */
public final class GuardedExecutor implements Executor {

    private static final Logger log = LoggerFactory.getLogger(GuardedExecutor.class);

    /** How much of the probe's message a reason quotes. */
    static final int MESSAGE_CHARS = 512;

    private final ProgramFailures.Subject subject;
    private final String name;
    private final TaskExecutionEnvironment environment;
    private final Executor delegate;
    private boolean guardDead;

    private GuardedExecutor(
            ProgramFailures.Subject subject, String name, TaskExecutionEnvironment environment, Executor delegate) {
        this.subject = subject;
        this.name = name;
        this.environment = environment;
        this.delegate = delegate;
    }

    /**
     * {@code executor}, bound to {@code environment}, behind the guard probe of that environment.
     *
     * @param subject what kind of executor a refusal names; never null
     * @param name the executor's name, as a refusal names it; never blank
     * @param environment the environment the executor is bound to; never null
     * @param executor the executor bound to it; never null
     * @return the decorated executor; never null
     */
    public static GuardedExecutor over(
            ProgramFailures.Subject subject, String name, TaskExecutionEnvironment environment, Executor executor) {
        return new GuardedExecutor(subject, name, environment, executor);
    }

    @Override
    public Describe describe() {
        return delegate.describe();
    }

    @Override
    public ExecutorResult start(ExecutorRequest request) {
        ExecutorResult refused = refusedBefore();
        return refused != null ? refused : checkedAfter(delegate.start(request));
    }

    @Override
    public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        ExecutorResult refused = refusedBefore();
        return refused != null ? refused : checkedAfter(delegate.poll(request, key));
    }

    /**
     * Whether a probe of this executor's requests found the guard dead.
     *
     * @return {@code true} once a probe threw
     */
    public boolean foundGuardDead() {
        return guardDead;
    }

    private @Nullable ExecutorResult refusedBefore() {
        String message = deadGuard();
        return message == null
                ? null
                : ProgramFailures.infrastructure(subject, name, cause("before", message), UntrustedText.factory(""));
    }

    private ExecutorResult checkedAfter(ExecutorResult answered) {
        String message = deadGuard();
        return message == null ? answered : ProgramFailures.discarded(subject, name, cause("after", message), answered);
    }

    private static String cause(String when, String message) {
        return "the egress guard of its environment was not running " + when + " the request: " + message;
    }

    /** The probe's message, rendered for the reason, when the guard is dead; {@code null} when it runs. */
    private @Nullable String deadGuard() {
        try {
            environment.probeGuard();
            return null;
        } catch (RuntimeException e) {
            guardDead = true;
            // throwable-not-subject: the request's failed/infrastructure carries the message; the
            //     engine writes the one operator line for it.
            log.debug("the egress guard of executor '{}' is not running", name, e);
            // The guard's message names its container and folds in the runtime's own answer, so it
            // leaves as container text.
            return UntrustedText.container(String.valueOf(e.getMessage())).excerpt(MESSAGE_CHARS);
        }
    }
}
