package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;

/**
 * Writes an {@link UntrustedText} field of a contract document as the JSON string it was read from
 * (FR1, NFR-S2 of define-executor-contract). A machine writer, and so an exit: the medium is a JSON
 * document whose own string encoding bounds the bytes, read back only through {@link
 * ResultFileReader}, which mints the text again. A document read back is therefore equal to the one
 * written, which is what the schema round-trip spec and the conformance kit rely on.
 *
 * <p>Implements FR1, NFR-S2 of define-executor-contract.
 */
@UntrustedExit
final class UntrustedTextSerializer extends StdSerializer<UntrustedText> {

    private static final long serialVersionUID = 1L;

    UntrustedTextSerializer() {
        super(UntrustedText.class);
    }

    @Override
    public void serialize(UntrustedText text, JsonGenerator generator, SerializerProvider provider) throws IOException {
        generator.writeString(text.raw());
    }
}
