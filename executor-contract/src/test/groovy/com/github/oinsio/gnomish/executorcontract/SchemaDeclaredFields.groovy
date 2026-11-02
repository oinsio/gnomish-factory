package com.github.oinsio.gnomish.executorcontract

import com.fasterxml.jackson.databind.JsonNode

/**
 * FR1, NFR-R3 of define-executor-contract (conformance-kit "Drift between DTO and schema is red"):
 * the fields of a written wire document that its schema file does not declare, each named by its
 * path. The schemas leave additional properties open on purpose — a newer executor's field is
 * ignored, not refused (FR4) — so validation alone cannot see a DTO that writes a field the schema
 * never names; this walk can. A field is declared where the schema at that place, through its
 * {@code $ref}, {@code allOf}, {@code oneOf}, {@code anyOf}, {@code then} and {@code else}
 * branches, lists it under {@code properties}; a map ({@code additionalProperties} with a schema)
 * walks each value against that schema; a place that declares no property at all (the free-form
 * {@code settings}, a SARIF location) is not walked. The schema files come from {@link WireSchemas},
 * their one owner.
 */
final class SchemaDeclaredFields {

    private SchemaDeclaredFields() {}

    /** The paths of the fields {@code written} carries that the {@code kind} schema does not declare. */
    static List<String> undeclared(WireDocument kind, JsonNode written) {
        def root = WireSchemas.document(kind)
        List<String> found = []
        walk(written, [new Place(root, root)], '$', found)
        found
    }

    private static void walk(JsonNode value, List<Place> schemas, String path, List<String> found) {
        List<Place> places = schemas.collectMany { expand(it) }
        if (value.isArray()) {
            def items = places.findAll {
                it.node.has('items')
            }.collect {
                it.at(it.node.get('items'))
            }
            if (items) {
                value.eachWithIndex { JsonNode item, int i ->
                    walk(item, items, "${path}[${i}]", found)
                }
            }
            return
        }
        if (!value.isObject()) {
            return
        }
        def declaring = places.findAll { it.node.has('properties') }
        def maps = places.findAll {
            it.node.get('additionalProperties')?.isObject()
        }
        .collect { it.at(it.node.get('additionalProperties')) }
        if (!declaring && !maps) {
            return
        }
        value.fields().each { entry ->
            def candidates = declaring.findAll {
                it.node.get('properties').has(entry.key)
            }
            .collect {
                it.at(it.node.get('properties').get(entry.key))
            } + maps
            if (candidates) {
                walk(entry.value, candidates, "${path}.${entry.key}", found)
            } else {
                found << "${path}.${entry.key}".toString()
            }
        }
    }

    /** The place itself, its reference resolved, and every branch it combines, recursively. */
    private static List<Place> expand(Place place) {
        List<Place> all = [place]
        JsonNode ref = place.node.get('$ref')
        if (ref != null) {
            all.addAll(expand(resolve(place, ref.asText())))
        }
        ['allOf', 'oneOf', 'anyOf'].each { combinator ->
            place.node.path(combinator).each { JsonNode branch ->
                all.addAll(expand(place.at(branch)))
            }
        }
        ['then', 'else'].each { branch ->
            if (place.node.has(branch)) {
                all.addAll(expand(place.at(place.node.get(branch))))
            }
        }
        all
    }

    private static Place resolve(Place place, String ref) {
        int hash = ref.indexOf('#')
        String file = hash < 0 ? ref : ref.substring(0, hash)
        String pointer = hash < 0 ? '' : ref.substring(hash + 1)
        JsonNode root = file.isEmpty() ? place.root : WireDocument.values().collect {
            WireSchemas.document(it)
        }
        .find { it.path('$id').asText().endsWith('/' + file) }
        assert root != null: "no schema file is ${file}"
        new Place(root.at(pointer), root)
    }

    /** A schema node and the document its local references resolve in. */
    private static final class Place {
        final JsonNode node
        final JsonNode root

        Place(JsonNode node, JsonNode root) {
            this.node = node
            this.root = root
        }

        Place at(JsonNode child) {
            new Place(child, root)
        }
    }
}
