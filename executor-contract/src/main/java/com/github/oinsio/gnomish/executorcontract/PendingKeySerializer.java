package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import java.io.IOException;

/**
 * Writes a {@link PendingKey} field of a contract document as the bare JSON string of its value —
 * the result schema's {@code pendingKey} (design D20 of migrate-verify-to-executor-contract). The
 * leaf type carries no JSON binding of its own, so the contract's one binding owns its wire form.
 * Write side only: no contract type binds the key on the read side, since a result is read from
 * {@link WrittenResult}'s string and {@link ResultReading} alone judges it and constructs the key.
 *
 * <p>Implements FR2 of migrate-verify-to-executor-contract.
 */
final class PendingKeySerializer extends StdSerializer<PendingKey> {

    private static final long serialVersionUID = 1L;

    PendingKeySerializer() {
        super(PendingKey.class);
    }

    @Override
    public void serialize(PendingKey key, JsonGenerator generator, SerializerProvider provider) throws IOException {
        generator.writeString(key.value());
    }
}
