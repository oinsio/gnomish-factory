package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.PendingTurn;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The one rendering of a pending key the operator's planes show (NFR-O1, UX3 of
 * migrate-verify-to-executor-contract): its first {@link #CHARS} code points, followed by {@code ...}
 * when the key is longer — enough to tell two keys apart and to find the whole key in the round's
 * pending record, never the whole opaque key, which may run to {@code PendingKey.MAX_LENGTH}
 * characters. The poll and timeout events ({@link EngineEvent.PendingPolled}, {@link
 * EngineEvent.PendingTimedOut}), which the logging listener renders, and the ledger's {@code
 * resumedKey} all take it from here.
 *
 * <p>A key is the executor's text, so the prefix stays a carrier: a key the instance read back from
 * the pending record is branch-document text, a key its own executor returned is the executor's
 * output, minted as a subprocess capture as the program adapter mints its keys.
 *
 * <p>Implements NFR-O1, UX3 of migrate-verify-to-executor-contract.
 */
final class PendingKeyPrefix {

    /** How many code points of a key are shown. */
    static final int CHARS = 16;

    private PendingKeyPrefix() {}

    /**
     * The shown prefix of {@code turn}'s key.
     *
     * @param turn the turn whose key is shown; never null
     * @return the prefix, carried in the family of the key's source; never null
     */
    static UntrustedText of(PendingTurn turn) {
        String key = turn.key().value();
        String prefix = key.codePointCount(0, key.length()) > CHARS
                ? key.substring(0, key.offsetByCodePoints(0, CHARS)) + "..."
                : key;
        return turn.origin() == PendingTurn.Origin.RECORDED
                ? UntrustedText.branchDocument(prefix)
                : UntrustedText.subprocess(prefix);
    }
}
