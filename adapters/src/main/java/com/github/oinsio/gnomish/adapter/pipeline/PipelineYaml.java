package com.github.oinsio.gnomish.adapter.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * The single YAML {@link ObjectMapper} the adapter reads {@code .gnomish/}
 * manifests with (D2). It parses YAML via {@link YAMLFactory} and deserializes
 * into the adapter's annotated DTO records.
 *
 * <p>Configured so that opaque {@code settings}/{@code params} maps bind to plain
 * JDK types (String/Number/Boolean/List/Map) rather than a Jackson
 * {@code JsonNode}: the DTO fields are typed {@code Map<String, Object>}, and
 * Jackson's untyped {@code Object} binding already yields plain JDK values, so
 * the domain mapper (task 5.3) can carry them across the boundary and keep the
 * domain Jackson-free (D5a, FR11). {@code FAIL_ON_UNKNOWN_PROPERTIES} is left on
 * (the default) so an unexpected key surfaces as a structural error the loader
 * can report (task 5.2, FR5).
 *
 * <p>This is only the parsing entry point: reading files, structural-error
 * capture, DTO→domain mapping and orchestration are later tasks (5.2, 5.3, 6.x).
 *
 * <p>Implements FR1, D2 of load-pipeline-config.
 */
public final class PipelineYaml {

    private PipelineYaml() {}

    /**
     * Builds a fresh YAML {@link ObjectMapper} for reading {@code .gnomish/}
     * DTOs. A new instance is returned per call — the adapter owns its lifetime;
     * the loader (task 6.5) may cache one if it wishes.
     *
     * @return a YAML-backed mapper configured for the pipeline DTOs
     */
    public static ObjectMapper mapper() {
        // Jackson 2.21 binds records natively and binds untyped Object fields to
        // plain JDK types, so the YAMLFactory alone gives the DTOs their shape
        // and keeps opaque settings/params as plain maps (D5a) — no extra module.
        return new ObjectMapper(new YAMLFactory());
    }
}
