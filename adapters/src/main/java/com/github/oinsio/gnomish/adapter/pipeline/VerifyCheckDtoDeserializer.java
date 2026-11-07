package com.github.oinsio.gnomish.adapter.pipeline;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Selects the {@link VerifyCheckDto} record of one {@code verify} entry (FR1, UX2 of
 * migrate-verify-to-executor-contract): an entry with a {@code type} binds to the record its
 * token names, exactly as the former name-based type information did; an entry with no {@code
 * type} but an {@code executor} key binds to {@link VerifyCheckDto.Executor}, the explicit form.
 *
 * <p>An unknown token, and an entry with neither key, fail as Jackson's own discriminator errors
 * ({@link InvalidTypeIdException} with and without a type id), so {@link StructuralParse} keeps
 * reporting them with the located messages it always had. The token is removed before binding:
 * the records do not declare it, and an undeclared field is an error.
 *
 * <p>Implements FR1, UX2 of migrate-verify-to-executor-contract; FR5 of load-pipeline-config.
 */
final class VerifyCheckDtoDeserializer extends StdDeserializer<VerifyCheckDto> {

    private static final Map<String, Class<? extends VerifyCheckDto>> TOKENS = Map.of(
            "builtin", VerifyCheckDto.Builtin.class,
            "command", VerifyCheckDto.Command.class,
            "external", VerifyCheckDto.External.class,
            "judge", VerifyCheckDto.Judge.class);

    VerifyCheckDtoDeserializer() {
        super(VerifyCheckDto.class);
    }

    @Override
    public VerifyCheckDto deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = context.readTree(parser);
        if (!(node instanceof ObjectNode entry)) {
            throw MismatchedInputException.from(parser, VerifyCheckDto.class, "a verify check is a mapping");
        }
        JsonNode token = entry.remove("type");
        if (token == null || token.isNull()) {
            if (entry.has("executor")) {
                return context.readTreeAsValue(entry, VerifyCheckDto.Executor.class);
            }
            throw typeIdError(parser, context, null);
        }
        Class<? extends VerifyCheckDto> target = TOKENS.get(token.asText());
        if (target == null) {
            throw typeIdError(parser, context, token.asText());
        }
        return context.readTreeAsValue(entry, target);
    }

    private static InvalidTypeIdException typeIdError(
            JsonParser parser, DeserializationContext context, @Nullable String typeId) {
        return InvalidTypeIdException.from(
                parser,
                typeId == null ? "missing verify check type" : "unknown verify check type",
                context.constructType(VerifyCheckDto.class),
                typeId);
    }
}
