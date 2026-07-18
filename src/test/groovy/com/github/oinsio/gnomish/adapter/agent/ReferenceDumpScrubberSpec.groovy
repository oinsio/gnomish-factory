package com.github.oinsio.gnomish.adapter.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.e2e.paidsmoke.ReferenceDumpScrubber
import spock.lang.Specification

/**
 * JSON deny-list contract for {@link ReferenceDumpScrubber}: a committed fixture
 * must never carry real spend or per-run identifiers, yet must preserve token/cache
 * counts. Runs under {@code check} from {@code adapter.agent} (D2). Implements FR1
 * (strip cost), FR2 (drop {@code permission_denials}), FR3 (remove every {@code
 * uuid}/{@code request_id} at any depth), FR4 (preserve model ids and token/cache
 * counts), NFR-R1 (idempotent second pass) and D1 (non-JSON input falls through to
 * string scrub) of harden-reference-dump-scrubber. The string-level path and
 * session-id scrubbing (FR5, FR7) is covered by {@code ReferenceDumpStringScrubSpec}.
 */
class ReferenceDumpScrubberSpec extends Specification {

    private static final ObjectMapper MAPPER = new ObjectMapper()

    def "strips all cost fields from a result line while preserving every model's token and cache counts"() {
        given: 'a real-shaped result line carrying total_cost_usd plus per-model costUSD and token counts'
        String rawLine = '{"type":"result","subtype":"success","is_error":false,' +
                '"result":"Stage complete.","session_id":"sess-abc-123",' +
                '"total_cost_usd":0.16131700000000002,' +
                '"usage":{"input_tokens":4099,"output_tokens":781},' +
                '"modelUsage":{' +
                '"claude-opus-4-1-20250805":{"inputTokens":4099,"outputTokens":781,' +
                '"cacheReadInputTokens":93494,"cacheCreationInputTokens":7395,' +
                '"webSearchRequests":0,"costUSD":0.160717,"contextWindow":1000000,"maxOutputTokens":64000},' +
                '"claude-haiku-4-5-20251001":{"inputTokens":530,"outputTokens":14,' +
                '"cacheReadInputTokens":0,"cacheCreationInputTokens":0,' +
                '"webSearchRequests":0,"costUSD":0.0006,"contextWindow":200000,"maxOutputTokens":32000}},' +
                '"permission_denials":[]}'

        when: 'the line is scrubbed'
        String scrubbed = ReferenceDumpScrubber.scrub(rawLine, '/private/workspace', 'sess-abc-123', 'plain')

        then: 'FR1: no cost field survives — neither the top-level total nor any per-model costUSD'
        !scrubbed.contains('total_cost_usd')
        !scrubbed.contains('costUSD')

        and: 'FR4: every resolved model id and its four token/cache counts survive intact'
        JsonNode modelUsage = MAPPER.readTree(scrubbed).get('modelUsage')
        modelUsage.fieldNames().toList() == [
            'claude-opus-4-1-20250805',
            'claude-haiku-4-5-20251001'
        ]
        modelUsage.properties().every { model ->
            JsonNode m = model.value
            m.has('inputTokens') && m.has('outputTokens') &&
                    m.has('cacheReadInputTokens') && m.has('cacheCreationInputTokens')
        }

        and: 'FR4: the surviving counts are the exact original values, not zeroed or dropped'
        JsonNode opus = modelUsage.get('claude-opus-4-1-20250805')
        opus.get('inputTokens').asInt() == 4099
        opus.get('outputTokens').asInt() == 781
        opus.get('cacheReadInputTokens').asInt() == 93494
        opus.get('cacheCreationInputTokens').asInt() == 7395
    }

    def "removes the entire permission_denials block, including its echoed raw tool inputs"() {
        given: 'a result line whose populated permission_denials echo raw sensitive tool inputs'
        String rawLine = '{"type":"result","subtype":"success","is_error":false,' +
                '"result":"Blocked.","session_id":"sess-abc-123",' +
                '"permission_denials":[' +
                '{"tool_name":"Write","tool_use_id":"toolu_015cfqtGcyEeyK2vP3vSmo1T",' +
                '"tool_input":{"file_path":"/private/workspace/SECRET-MARKER-42.txt","content":"done"}},' +
                '{"tool_name":"Bash","tool_use_id":"toolu_01VM7K4CXg3ZFKX5GUc9PAJt",' +
                '"tool_input":{"command":"printf SECRET-MARKER-42 > out.txt","description":"write"}}]}'

        when: 'the line is scrubbed'
        String scrubbed = ReferenceDumpScrubber.scrub(rawLine, '/private/workspace', 'sess-abc-123', 'plain')

        then: 'FR2: the permission_denials key is gone entirely'
        !scrubbed.contains('permission_denials')

        and: 'FR2: no raw tool-input token that lived only inside that block survives'
        !scrubbed.contains('SECRET-MARKER-42')

        and: 'FR2: the parsed tree has no permission_denials field at all'
        !MAPPER.readTree(scrubbed).has('permission_denials')
    }

    def "removes every per-event uuid and request_id, recursively by field name"() {
        given: 'a real-shaped assistant line carrying a top-level uuid, a request_id, and a nested uuid'
        String rawLine = '{"type":"assistant","session_id":"sess-abc-123",' +
                '"uuid":"3d3be167-0ace-4b42-a7a0-ca4a227aac66",' +
                '"request_id":"req_011Cd8hfSYpsiVVbV8KDJigd",' +
                '"message":{"id":"msg_01ABC","role":"assistant","model":"claude-opus-4-1-20250805",' +
                '"content":[{"type":"text","text":"Working."}],' +
                '"usage":{"input_tokens":4099,"output_tokens":781,' +
                '"cache_read_input_tokens":93494,"cache_creation_input_tokens":7395},' +
                '"uuid":"885fd49d-7fbd-44be-b7bb-52061ba320c4"}}'

        when: 'the line is scrubbed'
        String scrubbed = ReferenceDumpScrubber.scrub(rawLine, '/private/workspace', 'sess-abc-123', 'plain')

        then: 'FR3: no uuid or request_id key text survives anywhere in the line'
        !scrubbed.contains('"uuid"')
        !scrubbed.contains('"request_id"')

        and: 'FR3: the parsed tree has neither field at the top level'
        JsonNode tree = MAPPER.readTree(scrubbed)
        !tree.has('uuid')
        !tree.has('request_id')

        and: 'FR3: the nested uuid inside message is gone too — pruning is recursive by field name'
        !tree.get('message').has('uuid')

        and: 'FR3: the surrounding representative fields are structurally intact (session_id still scrubbed per FR5)'
        tree.get('session_id').asText() == 'ref-session-plain-1'
        tree.get('message').get('usage').get('input_tokens').asInt() == 4099
    }

    def "scrubbing an already-scrubbed line a second time is a byte-identical no-op"() {
        given: 'a result line carrying every deny-listed field so the first pass transforms it'
        String raw = '{"type":"result","session_id":"sess-abc-123","total_cost_usd":0.16,' +
                '"uuid":"3d3be167-0ace-4b42-a7a0-ca4a227aac66",' +
                '"request_id":"req_011Cd8hfSYpsiVVbV8KDJigd","permission_denials":[]}'

        when: 'the line is scrubbed once, then the scrubbed output is scrubbed again'
        String once = ReferenceDumpScrubber.scrub(raw, '/private/workspace', 'sess-abc-123', 'plain')
        String twice = ReferenceDumpScrubber.scrub(once, '/private/workspace', 'sess-abc-123', 'plain')

        then: 'NFR-R1: the second pass reproduces the first byte-for-byte'
        twice == once
    }

    // D1 fallback: a non-JSON line gets string scrubbing only; JSON pruning is skipped, no throw.
    def "returns non-JSON input with only string scrubbing applied, skipping JSON pruning"() {
        given: 'garbage that is not a JSON object but embeds a real session id and username'
        String rawLine = 'not-json: /Users/alice/secret and session 01JQ7-real-session-xyz'
        when: 'the un-parseable line is scrubbed'
        String scrubbed = ReferenceDumpScrubber.scrub(rawLine, '/private/workspace', '01JQ7-real-session-xyz', 'plain')
        then: 'D1: no exception is thrown and the string-level scrubbing still applied'
        scrubbed.contains('ref-session-plain-1')
        !scrubbed.contains('01JQ7-real-session-xyz')
        scrubbed.contains('/workspace-scrubbed/secret')
        !scrubbed.contains('/Users/alice')
    }
}
