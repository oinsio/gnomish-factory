package com.github.oinsio.gnomish.serveobservability

import com.fasterxml.jackson.databind.JsonNode
import com.github.oinsio.gnomish.serveobservability.json.LedgerJson
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJson
import java.time.Instant
import java.time.format.DateTimeParseException
import spock.lang.Specification

/**
 * Field-inventory / leak-prevention guardrail (NFR-S1): walks every leaf
 * field of the snapshot and ledger reference documents and asserts it falls
 * into one of five closed categories — identifier, state, counter,
 * timestamp, or token count. No field may carry task content, prompts, or
 * credentials.
 *
 * <p>Two independent checks per leaf, both must agree:
 * <ol>
 *   <li>the field's key (last path segment) is on an explicit allow-list
 *       mapping it to a {@link FieldCategory} — new fields MUST be added
 *       here deliberately, so an unreviewed field (e.g. a future {@code
 *       notes} or {@code prompt} string) fails closed instead of silently
 *       passing;
 *   <li>the field's runtime value shape matches that category (timestamps
 *       parse as {@link Instant}, counters are non-negative integers,
 *       identifiers/states are short single-token strings) — catching a
 *       key that is allow-listed but whose value has drifted into free text.
 * </ol>
 *
 * <p>The one dynamic key in either document — a {@code tokensByModel} map's
 * model-name keys (e.g. {@code "claude-sonnet-5"}) — is handled separately:
 * the key itself is validated as an identifier, its children as token counts.
 *
 * <p>Implements NFR-S1 of add-serve-observability (task 1.5), the "Field
 * inventory stays leak-free" scenario of {@code specs/serve-observability}.
 */
class FieldInventorySpec extends Specification {

    /** The closed set of field meanings NFR-S1 permits. */
    enum FieldCategory {
        IDENTIFIER, STATE, COUNTER, TIMESTAMP, TOKEN_COUNT
    }

    /**
     * Explicit allow-list: every leaf field name known to appear in the
     * snapshot or ledger contracts, mapped to its category. A field name
     * absent from this map fails the inventory — additions must be
     * deliberate.
     */
    static final Map<String, FieldCategory> ALLOWED_FIELDS = [
        // identifiers
        instanceId    : FieldCategory.IDENTIFIER,
        host          : FieldCategory.IDENTIFIER,
        factoryVersion: FieldCategory.IDENTIFIER,
        taskId        : FieldCategory.IDENTIFIER,
        stage         : FieldCategory.IDENTIFIER,
        type          : FieldCategory.IDENTIFIER,
        version       : FieldCategory.COUNTER,
        // states (closed vocabularies, or a short reason token)
        state         : FieldCategory.STATE,
        outcome       : FieldCategory.STATE,
        event         : FieldCategory.STATE,
        reason        : FieldCategory.STATE,
        parkReason    : FieldCategory.STATE,
        // counters
        intervalSeconds     : FieldCategory.COUNTER,
        openFronts          : FieldCategory.COUNTER,
        wipLimit            : FieldCategory.COUNTER,
        capacity            : FieldCategory.COUNTER,
        attempt             : FieldCategory.COUNTER,
        heldClaims          : FieldCategory.COUNTER,
        restartCount        : FieldCategory.COUNTER,
        consecutiveFailures : FieldCategory.COUNTER,
        attemptsUsed        : FieldCategory.COUNTER,
        wallMillis          : FieldCategory.COUNTER,
        delivered           : FieldCategory.COUNTER,
        awaitingHuman       : FieldCategory.COUNTER,
        aborted             : FieldCategory.COUNTER,
        revoked             : FieldCategory.COUNTER,
        // timestamps
        writtenAt    : FieldCategory.TIMESTAMP,
        since        : FieldCategory.TIMESTAMP,
        lastPollAt   : FieldCategory.TIMESTAMP,
        lastTickAt   : FieldCategory.TIMESTAMP,
        lastRunAt    : FieldCategory.TIMESTAMP,
        lastSuccessAt: FieldCategory.TIMESTAMP,
        startedAt    : FieldCategory.TIMESTAMP,
        finishedAt   : FieldCategory.TIMESTAMP,
        at           : FieldCategory.TIMESTAMP,
        // token counts
        input        : FieldCategory.TOKEN_COUNT,
        output       : FieldCategory.TOKEN_COUNT,
        cacheCreation: FieldCategory.TOKEN_COUNT,
        cacheRead    : FieldCategory.TOKEN_COUNT,
    ]

    /**
     * Container keys whose children are objects/arrays rather than leaves —
     * walking recurses through them without requiring them to be leaf
     * fields themselves.
     */
    static final Set<String> CONTAINER_KEYS = [
        'instance',
        'lifecycle',
        'feed',
        'slots',
        'entries',
        'vitals',
        'heartbeat',
        'reaper',
        'janitor',
        'tracker',
        'counts',
        'tokensByModel',
    ] as Set

    /** {@code tokensByModel}'s own children are dynamic model-id keys, not on the allow-list by name. */
    static final String TOKENS_BY_MODEL_KEY = 'tokensByModel'

    static final ISO_INSTANT = ~/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/
    static final SHORT_TOKEN = ~/^[A-Za-z0-9][A-Za-z0-9._\-]{0,63}$/

    def "snapshot reference document: every field is an identifier, state, counter, timestamp, or token count"() {
        given:
        def root = SnapshotJson.mapper().readTree(resourceStream('snapshot-v1.reference.json'))

        expect:
        assertSubtree('$', root, false)
    }

    def "ledger reference document: every field of every line is an identifier, state, counter, timestamp, or token count"() {
        given:
        def lines = resourceText('ledger-v1.reference.jsonl').readLines().findAll { !it.isBlank() }

        expect:
        lines.each { line ->
            def root = LedgerJson.mapper().readTree(line)
            assertSubtree('$', root, false)
        }
        lines.size() == 5
    }

    /**
     * Recursively walks a JSON subtree, classifying every leaf against
     * {@link #ALLOWED_FIELDS}. {@code underTokensByModel} marks that {@code
     * fieldName} is a dynamic model-id key (or a descendant of one) rather
     * than a contract field name, so it is checked as an identifier / its
     * children as token counts instead of via the allow-list.
     */
    private static boolean assertSubtree(String path, JsonNode node, boolean underTokensByModel) {
        if (node.isObject()) {
            node.fields().each { entry ->
                def key = entry.key
                def childPath = "${path}.${key}"
                boolean childUnderModel = underTokensByModel
                if (key == TOKENS_BY_MODEL_KEY) {
                    // children of tokensByModel are dynamic model-id keys
                    node.get(key).fields().each { modelEntry ->
                        assertIdentifier("${childPath}.${modelEntry.key}", modelEntry.key)
                        assertSubtree("${childPath}.${modelEntry.key}", modelEntry.value, true)
                    }
                    return
                }
                if (underTokensByModel) {
                    // model entry's own children: input/output/cacheCreation/cacheRead
                    assertLeaf(childPath, key, entry.value, FieldCategory.TOKEN_COUNT)
                    return
                }
                if (CONTAINER_KEYS.contains(key) || entry.value.isObject() || entry.value.isArray()) {
                    assertSubtree(childPath, entry.value, false)
                    return
                }
                assertLeafAllowListed(childPath, key, entry.value)
            }
        } else if (node.isArray()) {
            node.eachWithIndex { child, idx -> assertSubtree("${path}[${idx}]", child, underTokensByModel) }
        }
        true
    }

    private static void assertLeafAllowListed(String path, String key, JsonNode value) {
        def category = ALLOWED_FIELDS[key]
        assert category != null: "Field '${path}' (key '${key}') is not on the NFR-S1 allow-list — " +
        "possible task-content/prompt/credential leak, or a new field needs an explicit category"
        assertLeaf(path, key, value, category)
    }

    private static void assertIdentifier(String path, String value) {
        assert value ==~ SHORT_TOKEN: "Field '${path}': dynamic model-id key '${value}' does not look like a short identifier"
    }

    private static void assertLeaf(String path, String key, JsonNode value, FieldCategory category) {
        if (value.isNull()) {
            // Nullable fields (reason, stage, parkReason, tracker.lastSuccessAt, ...) are
            // legitimately absent-valued; null itself carries no content to leak.
            return
        }
        switch (category) {
            case FieldCategory.IDENTIFIER:
                assert value.isTextual(): "Field '${path}' (identifier) must be a string, was: ${value.nodeType}"
                assert value.textValue() ==~ SHORT_TOKEN:
                "Field '${path}' (identifier) value '${value.textValue()}' is not a short token — possible free text"
                break
            case FieldCategory.STATE:
                assert value.isTextual(): "Field '${path}' (state) must be a string, was: ${value.nodeType}"
                assert value.textValue() ==~ SHORT_TOKEN:
                "Field '${path}' (state) value '${value.textValue()}' is not a short token — possible free text"
                break
            case FieldCategory.COUNTER:
                assert value.isIntegralNumber(): "Field '${path}' (counter) must be an integer, was: ${value.nodeType}"
                assert value.longValue() >= 0: "Field '${path}' (counter) must be non-negative, was ${value.longValue()}"
                break
            case FieldCategory.TOKEN_COUNT:
                assert value.isIntegralNumber(): "Field '${path}' (token count) must be an integer, was: ${value.nodeType}"
                assert value.longValue() >= 0: "Field '${path}' (token count) must be non-negative, was ${value.longValue()}"
                break
            case FieldCategory.TIMESTAMP:
                assert value.isTextual(): "Field '${path}' (timestamp) must be a string, was: ${value.nodeType}"
                assert value.textValue() ==~ ISO_INSTANT:
                "Field '${path}' (timestamp) value '${value.textValue()}' is not ISO-8601 UTC shaped"
                assertParsesAsInstant(path, value.textValue())
                break
        }
    }

    private static void assertParsesAsInstant(String path, String text) {
        try {
            Instant.parse(text)
        } catch (DateTimeParseException e) {
            assert false: "Field '${path}' (timestamp) value '${text}' does not parse as an Instant: ${e.message}"
        }
    }

    private static InputStream resourceStream(String name) {
        FieldInventorySpec.getResourceAsStream("/${name}")
    }

    private static String resourceText(String name) {
        resourceStream(name).getText('UTF-8')
    }
}
