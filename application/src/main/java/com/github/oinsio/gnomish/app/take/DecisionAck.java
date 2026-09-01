package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.terminal.EffectObservation;
import com.github.oinsio.gnomish.app.terminal.TerminalEffect;
import com.github.oinsio.gnomish.app.terminal.TerminalEffectDrive;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The human decision as one intent→effect→receipt flow (FR10, FR12, design D5 of
 * harden-task-branch-contract): the decision commit on the task branch is the durable intent, the
 * acknowledge marker on the tracker is the effect, and the marker — written find-then-upsert
 * (FR11) — is its own receipt, so no third write exists.
 *
 * <p>The order is the point. Acknowledging first and committing second freezes, on a kill between
 * them, a task whose tracker says "answered" while its branch has no answer on it — the reply is
 * consumed and lost, because the next collection starts after the ack. Committing first freezes the
 * opposite: a decision durable on the branch whose reply is still pending, which the next pickup
 * re-drives into the same upserted marker (FR12).
 *
 * <p>Implements FR10, FR12 of harden-task-branch-contract; FR12 of add-tracker-port.
 */
public final class DecisionAck implements TerminalEffect {

    private static final Logger log = LoggerFactory.getLogger(DecisionAck.class);

    /** Appends the human's decision to the task branch, in one commit with the attempt reset. */
    @FunctionalInterface
    public interface DecisionIntent {

        /**
         * Commits the decision.
         *
         * @return the task context that now includes it; never null
         */
        TaskContext append();
    }

    /**
     * The intent of a re-drive: there is none. The decision is already on the branch, and {@link
     * TerminalEffectDrive#redeliver} never calls {@link #recordIntent()} — so a re-drive that
     * somehow reached it would be re-committing an answer it already holds, which this refuses
     * rather than silently duplicating.
     */
    private static final DecisionIntent ALREADY_COMMITTED = () -> {
        throw new IllegalStateException("a re-driven acknowledge never re-commits its decision");
    };

    private final Tracker tracker;
    private final TaskRef ref;
    private final String decisionText;
    private final DecisionIntent intent;
    private @Nullable TaskContext decided;

    private DecisionAck(Tracker tracker, TaskRef ref, String decisionText, DecisionIntent intent) {
        this.tracker = tracker;
        this.ref = ref;
        this.decisionText = decisionText;
        this.intent = intent;
    }

    /**
     * Commits {@code decisionText} to the branch and acknowledges it on the tracker, in that order,
     * returning the context the resumed run continues from.
     *
     * @param tracker the tracker port the acknowledge is posted through; never null
     * @param ref the task's tracker identity; never null
     * @param decisionText the human's reply, as posted; never blank
     * @param intent commits the decision to the branch; never null
     * @return the task context including the decision; never null
     */
    public static TaskContext appendThenAcknowledge(
            Tracker tracker, TaskRef ref, String decisionText, DecisionIntent intent) {
        var ack = new DecisionAck(tracker, ref, decisionText, intent);
        TerminalEffectDrive.deliverFresh(ack);
        return ack.decidedContext();
    }

    /**
     * Re-drives the acknowledge for a decision already durable on the branch — the kill window
     * between the commit and the marker (FR12). The tracker is probed first: a reply that is no
     * longer pending means the marker landed and nothing is written.
     *
     * @param tracker the tracker port the acknowledge is posted through; never null
     * @param ref the task's tracker identity; never null
     * @param decided the branch's own context, decision included; never null
     * @param decisionText the recorded decision to acknowledge; never blank
     */
    public static void redriveAcknowledge(Tracker tracker, TaskRef ref, TaskContext decided, String decisionText) {
        var ack = new DecisionAck(tracker, ref, decisionText, ALREADY_COMMITTED);
        ack.decided = decided;
        TerminalEffectDrive.redeliver(ack);
    }

    /**
     * Whether {@code replies} still holds a reply whose text the branch has already recorded as its
     * last decision — the signature of an intent whose acknowledge never landed.
     *
     * @param replies the replies the tracker reports as posted since the last acknowledge
     * @param decided the branch's own context
     * @return the recorded decision awaiting its acknowledge, or {@code null} when there is none
     */
    public static @Nullable String unacknowledged(List<HumanReply> replies, TaskContext decided) {
        if (replies.isEmpty() || decided.decisions().isEmpty()) {
            return null;
        }
        String lastRecorded = decided.decisions().getLast().body();
        return replies.stream().anyMatch(reply -> reply.body().equals(lastRecorded)) ? lastRecorded : null;
    }

    @Override
    public void recordIntent() {
        decided = intent.append();
    }

    /**
     * A tracker reporting no pending reply has already consumed this decision: the acknowledge
     * landed and only its record was lost. An unaskable tracker re-drives, which the upserted
     * marker makes safe (FR11).
     */
    @Override
    public EffectObservation observeAtTarget() {
        try {
            return tracker.collectDecisions(ref).isEmpty() ? EffectObservation.LANDED : EffectObservation.ABSENT;
        } catch (RuntimeException e) {
            log.warn("could not verify whether the decision acknowledge of {} landed", ref.id(), e);
            return EffectObservation.UNDETERMINED;
        }
    }

    /**
     * PIT documented exception (`.claude/rules/testing.md`, "provably equivalent mutant"):
     * {@code return true} carries no observable consequence for this flow. A {@code false} would
     * only make {@link TerminalEffectDrive} skip {@link #recordReceipt()} — a no-op here, since the
     * upserted marker is its own receipt — and the {@code EffectDelivery} both entry points discard.
     * The acknowledge itself is asserted directly by {@code DecisionAckSpec}'s "posts it once" and
     * "posts nothing" scenarios, so the coverage stands; only the unkillable mutation is exempted.
     */
    @DoNotMutate
    @Override
    public boolean deliver() {
        tracker.acknowledgeDecision(ref, decisionText);
        return true;
    }

    @Override
    public void recordReceipt() {
        // The acknowledge marker is its own receipt: it is written find-then-upsert (FR11), so a
        // re-drive updates it in place rather than adding a second one.
    }

    private TaskContext decidedContext() {
        TaskContext context = decided;
        if (context == null) {
            throw new IllegalStateException("the decision intent must produce the context it committed");
        }
        return context;
    }
}
