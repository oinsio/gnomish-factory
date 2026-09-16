package com.github.oinsio.gnomish.adapter.agent.fake

/**
 * Reads back one committed fake-agent fixture's raw stdout
 * ({@code src/test/resources/fake-agent/scenarios/&lt;scenario&gt;/stdout.jsonl}) as a
 * {@link BufferedReader}, ready for {@code StreamJsonParser.parse}.
 *
 * <p>Extracted to remove a verbatim-duplicated {@code readerOf(String)} helper from every
 * spec that plays back these fixtures (StreamJsonParserFixtureSpec, StreamJsonParserProgressSpec,
 * and others) — the resource path is classpath-absolute, so the reading class is irrelevant to
 * resolution and one shared helper is correct for all of them.
 *
 * <p>Not production code: test-support only, never PIT-mutated.
 */
class FakeAgentScenarioReader {

    private FakeAgentScenarioReader() {
    }

    static BufferedReader readerOf(String scenario) {
        def resource = FakeAgentScenarioReader.getResource("/fake-agent/scenarios/${scenario}/stdout.jsonl")
        assert resource != null: "fixture not found for scenario '${scenario}'"
        new BufferedReader(new InputStreamReader(resource.openStream(), 'UTF-8'))
    }
}
