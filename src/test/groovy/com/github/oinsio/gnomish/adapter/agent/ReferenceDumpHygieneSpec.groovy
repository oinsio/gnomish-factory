package com.github.oinsio.gnomish.adapter.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.e2e.paidsmoke.ReferenceDumpScrubber
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Requires
import spock.lang.Specification

/**
 * Hygiene guard over the four committed {@code *.reference.json} fixtures under
 * {@code src/test/resources/stream-json-reference/}: always-on assertions prove
 * they stay scrubbed of sensitive data, and a gated feature re-scrubs them in
 * place via {@link ReferenceDumpScrubber}.
 *
 * <p><b>Placement (D2):</b> lives in {@code adapter.agent}, not {@code
 * e2e.paidsmoke} (which {@code build.gradle} excludes from the {@code test}
 * task), so it runs under {@code check}; fixtures resolve from the {@code
 * referenceDumpDir} system property (on-disk resources, not the build copy).
 *
 * <p>Implements FR6, M1, M2, M3, M4, G3, NFR-S1, NFR-R1, D2 of harden-reference-dump-scrubber.
 */
class ReferenceDumpHygieneSpec extends Specification {

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /** The four committed fixtures under {@code stream-json-reference/}, in a stable order. */
    static final List<String> FIXTURE_NAMES = List.of(
    'plain-round.reference.json',
    'subagent-round.reference.json',
    'judge-verdict.reference.json',
    'result-without-model-usage.reference.json')

    /** The three real recordings that carry a per-model {@code modelUsage} block (the placeholder does not). */
    static final List<String> REFRESHED_FIXTURE_NAMES = List.of(
    'plain-round.reference.json',
    'subagent-round.reference.json',
    'judge-verdict.reference.json')

    // FR6: resolve every committed fixture via referenceDumpDir and confirm it is a readable file
    def "resolves all four reference fixtures via referenceDumpDir as readable files"() {
        expect: 'each named fixture resolves to an existing, readable regular file'
        FIXTURE_NAMES.every { name ->
            Path fixture = resolveFixture(name)
            Files.isRegularFile(fixture) && Files.isReadable(fixture)
        }
    }

    // FR6, D2: gated rewrite — skipped in a normal `check` run so the build never mutates committed
    // sources; run explicitly via -DrescrubReferenceDumps=true. Empty path/session args make the
    // path/session replacements no-ops (scrubber guards empty needles); only JSON deny-list pruning runs.
    @Requires({ System.getProperty('rescrubReferenceDumps') })
    def "re-scrubs every committed fixture in place (gated on -DrescrubReferenceDumps)"() {
        expect: 'each committed fixture is re-scrubbed line by line and written back in place'
        FIXTURE_NAMES.each { name ->
            Path fixture = resolveFixture(name)
            List<String> scrubbed = readFixtureLines(name)
                    .collect { line -> ReferenceDumpScrubber.scrub(line, '', '', '') }
            Files.write(fixture, scrubbed, StandardCharsets.UTF_8)
            // NFR-R1, M4: a second in-memory pass over the just-written file changes nothing (idempotent).
            List<String> secondPass = readFixtureLines(name)
                    .collect { line -> ReferenceDumpScrubber.scrub(line, '', '', '') }
            assert secondPass == scrubbed
        }
    }

    /** Sensitive tokens that must never appear in any committed fixture (M2 grep targets). */
    static final List<String> FORBIDDEN_TOKENS = List.of(
    'total_cost_usd',
    'costUSD',
    'permission_denials',
    '"uuid"',
    '"request_id"')

    // M1, M2: every committed fixture (incl. the hand-authored placeholder) must be free of
    // sensitive tokens — this always-on guard names the offending fixture/token in its report.
    def "committed fixture #fixtureName never contains forbidden token #token"() {
        given: 'the full text of the fixture as committed on disk'
        String content = readFixtureLines(fixtureName).join('\n')

        expect: 'the sensitive token appears nowhere in the fixture content'
        !content.contains(token)

        where: 'each of the four fixtures is checked against each forbidden token'
        [fixtureName, token] << [
            FIXTURE_NAMES,
            FORBIDDEN_TOKENS
        ].combinations()
    }

    /**
     * Machine-/user-identifying path patterns that must survive nowhere in a committed fixture
     * (FR7, NFR-S1 grep targets): home dirs, the macOS per-user {@code var/folders} temp hash (both
     * slash and dashed-encoded forms), and the per-uid {@code tmp/claude-<uid>} dir. Opaque per-run
     * tokens ({@code agentId}/{@code task_id}) are intentionally absent — they are kept (NG3).
     */
    static final List<String> FORBIDDEN_PATH_PATTERNS = List.of(
    /\/(Users|home)\//,
    /var[-\/]folders/,
    /(\/private)?\/tmp\/claude-\d/)

    // NFR-S1, FR7: no absolute home- or temp-directory path (or its dashed encoding) may survive in
    // any committed dump — mirrors the success-metric greps over stream-json-reference (0 hits).
    def "committed fixture #fixtureName leaks no machine path matching #pattern"() {
        given: 'every line of the fixture as committed on disk'
        List<String> lines = readFixtureLines(fixtureName)

        expect: 'no line contains a machine-/user-identifying path segment'
        String offending = lines.find { line -> line =~ pattern }
        offending == null

        where: 'each committed fixture is scanned against each forbidden path pattern'
        [fixtureName, pattern] << [
            FIXTURE_NAMES,
            FORBIDDEN_PATH_PATTERNS
        ].combinations()
    }

    // M3, G3: each refreshed recording must still carry resolved model ids plus the four
    // per-model token/cache counts under modelUsage — proof realism survived scrubbing.
    def "refreshed fixture #fixtureName keeps resolved model ids and their four token/cache counts"() {
        given: 'the parsed result event of the fixture as committed on disk'
        JsonNode modelUsage = resultEvent(fixtureName).path('modelUsage')

        expect: 'modelUsage is a non-empty object of per-model entries'
        modelUsage.isObject()
        modelUsage.size() > 0

        and: 'every entry key is a non-blank resolved model id carrying all four camelCase counts'
        modelUsage.properties().every { entry ->
            JsonNode m = entry.value
            !entry.key.isBlank() &&
                    m.has('inputTokens') && m.has('outputTokens') &&
                    m.has('cacheReadInputTokens') && m.has('cacheCreationInputTokens')
        }

        where: 'each of the three refreshed recordings is checked'
        fixtureName << REFRESHED_FIXTURE_NAMES
    }

    // M3, G3: the hand-authored placeholder carries no modelUsage — its realism lives in the
    // top-level snake_case usage block, which must survive with its token fields intact.
    def "placeholder fixture keeps its top-level usage token fields"() {
        given: 'the parsed result event of the placeholder fixture'
        JsonNode usage = resultEvent('result-without-model-usage.reference.json').path('usage')

        expect: 'the usage object exists and carries the four snake_case token/cache counts'
        usage.isObject()
        usage.has('input_tokens')
        usage.has('output_tokens')
        usage.has('cache_creation_input_tokens')
        usage.has('cache_read_input_tokens')
    }

    /** Parses each fixture line and returns the single {@code result} event node (M3, G3 realism checks). */
    protected static JsonNode resultEvent(String fixtureName) {
        JsonNode result = readFixtureLines(fixtureName)
                .collect { line -> MAPPER.readTree(line) }
                .find { node -> node.path('type').asText() == 'result' }
        assert result != null: "no result event found in ${fixtureName}"
        result
    }

    /** Resolves one fixture name to its {@link Path} under the {@code referenceDumpDir} directory. */
    protected static Path resolveFixture(String fixtureName) {
        referenceDumpDir().resolve(fixtureName)
    }

    /** Reads all lines of one fixture (UTF-8) — the raw stream-json lines a scrub check operates on. */
    protected static List<String> readFixtureLines(String fixtureName) {
        Files.readAllLines(resolveFixture(fixtureName))
    }

    /**
     * The on-disk {@code stream-json-reference} directory from the {@code referenceDumpDir} system
     * property (mirrors {@code PaidSmokeReferenceDumpSpec.referenceDumpDir()}); fails with a clear
     * message when unset/blank or not a directory.
     */
    protected static Path referenceDumpDir() {
        String configured = System.getProperty('referenceDumpDir')
        assert configured != null && !configured.isBlank():
        'test task must set the referenceDumpDir system property (see build.gradle)'
        Path dir = Path.of(configured)
        assert Files.isDirectory(dir): "referenceDumpDir does not exist: ${dir}"
        dir
    }
}
