package com.github.oinsio.gnomish.adapter.git.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;

/**
 * Shared version gate for reading {@code .gnomish-task/} state files (design
 * D5), used by {@link TaskJsonMapper#readDto(String)} and {@link
 * StateJsonMapper#readDto(String)} today, and intended for the future resume
 * flow and {@code status}/{@code usage} readers (tasks 2+, 5.2) to reuse
 * directly rather than re-implement.
 *
 * <p>Parses the raw JSON only as far as a {@link JsonNode} tree first and
 * checks {@code "version"} before attempting to bind the full DTO shape: a
 * file from an unsupported future version may not even bind cleanly to the
 * current record tree, so the version must be known-good before full binding
 * is attempted. Unknown fields elsewhere remain tolerated by {@link
 * TaskStateJson#mapper()}'s {@code FAIL_ON_UNKNOWN_PROPERTIES=false} setting.
 *
 * <p>Implements FR4 of add-git-workflow.
 */
final class StateFileVersionGate {

    private StateFileVersionGate() {}

    /**
     * Version-gates {@code json}, then binds it into {@code dtoType}.
     *
     * @param mapper the configured mapper to parse and bind with
     * @param fileName the state file's name for error messages, e.g. {@code
     *     "task.json"}
     * @param json the raw JSON text, e.g. read from a {@code git show} blob
     * @param supportedVersion the only version this build accepts
     * @param dtoType the DTO record type to bind the full document into
     * @param <T> the DTO type
     * @return the parsed and bound DTO
     * @throws UnsupportedStateFileVersionException if {@code "version"} is
     *     missing or does not equal {@code supportedVersion}
     * @throws UncheckedIOException if {@code json} is not valid JSON or does not
     *     bind to {@code dtoType} once past the version gate
     */
    static <T> T readGated(ObjectMapper mapper, String fileName, String json, int supportedVersion, Class<T> dtoType) {
        JsonNode root = readTree(mapper, fileName, json);
        JsonNode versionNode = root.get("version");
        int foundVersion = versionNode == null || !versionNode.canConvertToInt() ? -1 : versionNode.asInt();
        if (foundVersion != supportedVersion) {
            throw new UnsupportedStateFileVersionException(fileName, foundVersion, supportedVersion);
        }
        return bind(mapper, fileName, json, dtoType);
    }

    private static JsonNode readTree(ObjectMapper mapper, String fileName, String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(fileName + ": malformed JSON", e);
        }
    }

    private static <T> T bind(ObjectMapper mapper, String fileName, String json, Class<T> dtoType) {
        try {
            return mapper.readValue(json, dtoType);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(fileName + ": failed to bind " + dtoType.getSimpleName(), e);
        }
    }
}
