package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.io.IOException;

/**
 * Binds an optional boolean flag of the contract whose absence means {@code false} (the describe's
 * {@code checkIdIsPinnedPath}, design D17 of migrate-verify-to-executor-contract): an absent field
 * reads as {@code false}, so a document written before the flag existed reads unchanged; a JSON
 * {@code null} or any token other than a JSON boolean fails the read, as for every other primitive
 * of the contract ({@link ExecutorContractJson}). The flag guards a pin, so a value the schema
 * refuses is never read as either answer.
 *
 * <p>Implements FR16 of migrate-verify-to-executor-contract.
 */
final class AbsentFalseFlagDeserializer extends StdDeserializer<Boolean> {

    private static final long serialVersionUID = 1L;

    /** The constructor Jackson instantiates the deserializer with, from the field's annotation. */
    AbsentFalseFlagDeserializer() {
        super(Boolean.class);
    }

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.hasToken(JsonToken.VALUE_TRUE)) {
            return Boolean.TRUE;
        }
        if (parser.hasToken(JsonToken.VALUE_FALSE)) {
            return Boolean.FALSE;
        }
        throw MismatchedInputException.from(
                parser, Boolean.class, "a contract flag is a JSON boolean, got " + parser.currentToken());
    }

    @Override
    public Boolean getNullValue(DeserializationContext context) throws JsonMappingException {
        throw MismatchedInputException.from(
                context.getParser(), Boolean.class, "a contract flag is a JSON boolean, got null");
    }

    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return false;
    }
}
