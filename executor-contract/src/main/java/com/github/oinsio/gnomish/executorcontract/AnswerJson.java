package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.util.function.Function;

/**
 * Jackson's binding of {@link Answer}, through the contract's own reading and writing so that
 * Jackson and {@link Answer#read} cannot disagree: the writer is {@link AnswerWriter}, the reader is
 * {@link AnswerReader}, and a malformed answer read through Jackson is a {@link
 * JsonMappingException} naming the reason. A read carries the family {@link ResultFileReader}
 * opened it with; an answer read anywhere else fails, as any carrier field does.
 *
 * <p>Implements FR3 of define-executor-contract.
 */
final class AnswerJson {

    private AnswerJson() {}

    /** Writes an answer as its wire document. */
    static final class Writer extends StdSerializer<Answer> {

        private static final long serialVersionUID = 1L;

        Writer() {
            super(Answer.class);
        }

        @Override
        public void serialize(Answer answer, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeTree(AnswerWriter.write(answer));
        }
    }

    /** Reads an answer from its wire document, refusing a malformed one. */
    static final class Reader extends StdDeserializer<Answer> {

        private static final long serialVersionUID = 1L;

        Reader() {
            super(Answer.class);
        }

        @Override
        public Answer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            Function<String, UntrustedText> mint = UntrustedTextDeserializer.mintOf(parser, context);
            JsonNode written = parser.readValueAsTree();
            return switch (Answer.read(written, mint)) {
                case AnswerReading.Parsed parsed -> parsed.answer();
                case AnswerReading.Malformed malformed ->
                    throw JsonMappingException.from(
                            parser, "malformed answer: " + malformed.reason().forLog());
            };
        }
    }
}
