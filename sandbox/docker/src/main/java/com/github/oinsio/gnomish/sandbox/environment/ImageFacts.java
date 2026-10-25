package com.github.oinsio.gnomish.sandbox.environment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What one {@code docker image inspect} of an image's {@code Config} says about it (design D3 of
 * add-sandbox-base-image): the paths its Dockerfile declares with {@code VOLUME} and its labels.
 * The single owner of that read — {@link #read} issues the one inspection per image per
 * environment, and every consumer takes the value rather than asking the runtime again: {@link
 * DeclaredVolumeOverrides#resolve} for the sandbox and the guard image, and the image-contract
 * check for the sandbox image.
 *
 * <p>The answer is attacker-influenced (an image author chose the paths and the labels), so the
 * parse fails closed: a shape other than the runtime's own is refused rather than read as "the
 * image declares nothing" (NFR-R1 of fix-image-declared-volumes, design D4) — an empty set on a
 * misread would silently recreate the anonymous-volume leak. Label values stay in their carrier:
 * a consumer that renders one does so through the carrier's own exit.
 *
 * <p>Implements FR5, NFR-R1 of add-sandbox-base-image; FR1, NFR-R1 of fix-image-declared-volumes.
 *
 * @param image the image reference the facts were read for; never blank
 * @param declaredVolumes the declared volume paths, in the runtime's own order; never null
 * @param labels the image's labels, in the runtime's own order, each value as the runtime wrote it;
 *     never null
 */
@UntrustedParser
record ImageFacts(String image, List<String> declaredVolumes, Map<String, UntrustedText> labels) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    ImageFacts {
        declaredVolumes = List.copyOf(declaredVolumes);
        labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }

    /**
     * The one inspection: asks the runtime for the image's {@code Config} and parses it.
     *
     * @param docker the runtime seam to ask; never null
     * @param image the image reference to inspect; never blank
     * @return the image's facts; never null
     * @throws DockerCommandFailedException if the runtime refused the inspect or answered a shape
     *     other than the one it writes
     */
    static ImageFacts read(DockerCli docker, String image) {
        DockerResult result = docker.run(DockerCommands.inspectImageConfig(image));
        if (!result.ok()) {
            throw new DockerCommandFailedException("image inspect", image, null, result.stderr());
        }
        return parse(image, result.stdout());
    }

    /**
     * Parses an answer to {@link DockerCommands#inspectImageConfig}. The runtime writes the {@code
     * Config} as a JSON object (or {@code null} for an image with no configuration at all), whose
     * {@code Volumes} is {@code null}, absent or an object keyed by path, and whose {@code Labels}
     * is {@code null}, absent or an object of string values.
     */
    static ImageFacts parse(String image, UntrustedText answer) {
        JsonNode config;
        try {
            // @UntrustedParser warrant (design D11 of type-untrusted-text): docker's inspect answer
            //     becomes the declared volume paths, which travel back to docker as mount
            //     destinations, and the labels, whose values stay in the carrier; the answer itself
            //     leaves this class only through the carrier's log exit, in the messages below.
            config = MAPPER.readTree(answer.forParsing());
        } catch (JsonProcessingException e) {
            throw refused(image, "unparseable image configuration: " + answer, e);
        }
        if (config.isNull()) {
            return new ImageFacts(image, List.of(), Map.of());
        }
        if (!config.isObject()) {
            throw refused(image, "image configuration of an unexpected shape: " + answer, null);
        }
        return new ImageFacts(
                image, volumes(image, config.path("Volumes"), answer), labels(image, config.path("Labels"), answer));
    }

    private static List<String> volumes(String image, JsonNode node, UntrustedText answer) {
        List<String> declared = new ArrayList<>();
        for (Map.Entry<String, JsonNode> property : members(image, node, "declared volumes", answer)) {
            declared.add(property.getKey());
        }
        return declared;
    }

    private static Map<String, UntrustedText> labels(String image, JsonNode node, UntrustedText answer) {
        Map<String, UntrustedText> labels = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : members(image, node, "labels", answer)) {
            if (!property.getValue().isTextual()) {
                throw refused(image, "labels of an unexpected shape: " + answer, null);
            }
            labels.put(
                    property.getKey(),
                    UntrustedText.subprocess(property.getValue().asText()));
        }
        return labels;
    }

    /** The members of an object-valued field; {@code null} and absent are "none", anything else is refused. */
    private static Iterable<Map.Entry<String, JsonNode>> members(
            String image, JsonNode node, String what, UntrustedText answer) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isObject()) {
            throw refused(image, what + " of an unexpected shape: " + answer, null);
        }
        return node.properties();
    }

    private static DockerCommandFailedException refused(String image, String detail, @Nullable Throwable cause) {
        UntrustedText text = UntrustedText.subprocess(detail);
        return cause == null
                ? new DockerCommandFailedException("image inspect", image, null, text)
                : new DockerCommandFailedException("image inspect", image, null, text, cause);
    }
}
