package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The open round as its verification sees it (design D21 of migrate-verify-to-executor-contract): the
 * round's identity — its attempt key and the round token {@link RoundExecution} took once before
 * verification — the state the round's pending entries ride on, and the entries themselves, one per
 * check of the round that went {@code pending}.
 *
 * <p>An entry of the state handed in is the round's own only by identity (crash-consistency items
 * 13 and 14), as {@link PendingEntries#ofRound} judges it: its {@code attempt} is the round's number
 * and its {@code token} equals the round's token. A carried-over entry — another attempt, another
 * round's token, or any entry when the round has no token (the in-place run, which has no branch and
 * no resume) — is never continued and never written again; the list's presence decides nothing. A
 * {@link PendingSubject.Round Round} entry is never a verification's: the round's close drops it
 * before its checks run (design D11 of extract-agent-round-runner), so one still present is not
 * carried into a check's pending write either.
 *
 * <p>An entry stays in the list after its check has ended, until the round closes (D21), so every
 * pending write carries every key of the round so far: a kill while a later check is polled loses
 * no earlier key. The state written is the handed-in state with the list replaced and nothing else
 * changed, so a pending write names no outcome (item 12).
 *
 * <p>One instance per verification, confined to the thread that runs it: the list grows as the
 * round's checks answer {@code pending}, in check order, and an entry is replaced in its place when
 * its key rotates.
 *
 * <p>Implements FR2, FR3, FR10, FR20, NFR-R1 of migrate-verify-to-executor-contract.
 */
final class PendingRound {

    private final StageDefinition stage;
    private final TaskContext context;
    private final Workspace workspace;
    private final AttemptKey key;
    private final TaskState state;
    private final @Nullable RoundToken token;
    private final List<PendingPollRecord> entries = new ArrayList<>();

    /**
     * @param stage the stage whose round is verified; never null
     * @param context the task context a check request carries; never null
     * @param workspace the working copy the checks judge; never null
     * @param key the round's attempt key; never null
     * @param state the state the round's pending entries ride on — the last persisted state, carrying
     *     the entries a resume read; never null
     * @param token the round's token, or {@code null} in the in-place run
     */
    PendingRound(
            StageDefinition stage,
            TaskContext context,
            Workspace workspace,
            AttemptKey key,
            TaskState state,
            @Nullable RoundToken token) {
        this.stage = stage;
        this.context = context;
        this.workspace = workspace;
        this.key = key;
        this.state = state;
        this.token = token;
        for (var entry : state.pendingPolls()) {
            if (ours(entry)) {
                entries.add(entry);
            }
        }
    }

    /** The round's attempt key, the correlation key of the events its checks' waits emit. */
    AttemptKey key() {
        return key;
    }

    /** The round's token, or {@code null} in the in-place run. */
    @Nullable
    RoundToken token() {
        return token;
    }

    /** The working copy the round's checks judge. */
    Workspace workspace() {
        return workspace;
    }

    /**
     * The round's own entry of {@code check}, the one a pickup continues instead of a {@code start}.
     *
     * @param check the check's identity in the round
     * @return the entry, or empty when the round holds none for the check
     */
    Optional<PendingPollRecord> entryOf(CheckRef check) {
        return entries.stream()
                .filter(entry -> switch (entry.subject()) {
                    case PendingSubject.Check(CheckRef ref) -> ref.equals(check);
                    case PendingSubject.Round _ -> false;
                })
                .findFirst();
    }

    /**
     * Adds {@code entry} to the round's list and returns the state the pending write carries: the
     * handed-in state with the whole list so far, nothing else changed.
     *
     * @param entry the entry of a check whose executor answered {@code pending}; never null
     * @return the state to persist; never null, its list never empty
     */
    TaskState withEntry(PendingPollRecord entry) {
        entries.add(entry);
        return state.withPendingPolls(entries);
    }

    /**
     * Replaces the entry of {@code rotated}'s subject with {@code rotated} and returns the state the
     * rotation's pending write carries: the handed-in state with the whole list, every other entry
     * carried unchanged and in its place (D12 as amended 2026-11-12, D21). The entry is matched by
     * its identity alone, so the rotation is the same for any subject an entry names.
     *
     * @param rotated the entry with the key a {@code poll} returned; never null, its subject's entry
     *     already in the round's list
     * @return the state to persist; never null
     */
    TaskState withRotated(PendingPollRecord rotated) {
        entries.replaceAll(entry -> entry.subject().equals(rotated.subject()) ? rotated : entry);
        return state.withPendingPolls(entries);
    }

    /**
     * The check round's request: the round's context, stage, working copy and attempt, the check as
     * its subject with its identity in the round; a poll takes the turn beside it ({@link
     * StageExecutor#poll}).
     *
     * @param check the check the round judges; never null
     * @param ref the check's identity in the round, the subject its pending runs are keyed by; never
     *     null
     * @return the request; never null
     */
    StageExecutor.Request request(VerifyCheck.Executor check, CheckRef ref) {
        return new StageExecutor.Request(context, stage, workspace, key.attempt(), List.of(), check, ref);
    }

    private boolean ours(PendingPollRecord entry) {
        return switch (entry.subject()) {
            case PendingSubject.Check _ -> PendingEntries.ofRound(entry, key.attempt(), token);
            case PendingSubject.Round _ -> false;
        };
    }
}
