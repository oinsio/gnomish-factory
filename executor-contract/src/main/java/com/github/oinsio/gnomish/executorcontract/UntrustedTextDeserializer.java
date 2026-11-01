package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.util.function.Function;

/**
 * Binds a JSON string to an {@link UntrustedText} in the family the read was opened with (FR6,
 * NFR-S2 of define-executor-contract). Only {@link ResultFileReader} opens a read with a family, so
 * a document type with a carrier field read anywhere else fails rather than minting in a family
 * nobody chose. A token other than a string is a mismatch, never coerced to text.
 *
 * <p>Implements FR6, NFR-S2 of define-executor-contract.
 */
final class UntrustedTextDeserializer extends StdDeserializer<UntrustedText> {

    /** The read attribute that carries the {@link TextMint}. */
    static final Object MINT_ATTRIBUTE = TextMint.class;

    private static final long serialVersionUID = 1L;

    UntrustedTextDeserializer() {
        super(UntrustedText.class);
    }

    @Override
    public UntrustedText deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            throw MismatchedInputException.from(
                    parser,
                    UntrustedText.class,
                    "an untrusted-text field is a JSON string, got " + parser.currentToken());
        }
        return mintOf(parser, context).apply(parser.getText());
    }

    /**
     * The family the read was opened with; refuses a read {@link ResultFileReader} did not open.
     *
     * @throws JsonMappingException if the read carries no family
     */
    static Function<String, UntrustedText> mintOf(JsonParser parser, DeserializationContext context)
            throws JsonMappingException {
        if (!(context.getAttribute(MINT_ATTRIBUTE) instanceof TextMint textMint)) {
            throw JsonMappingException.from(
                    parser, "an untrusted-text field is read only through ResultFileReader, which names its family");
        }
        return textMint.mint();
    }
}
