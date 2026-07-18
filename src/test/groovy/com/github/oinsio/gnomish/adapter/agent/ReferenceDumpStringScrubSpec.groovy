package com.github.oinsio.gnomish.adapter.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.e2e.paidsmoke.ReferenceDumpScrubber
import spock.lang.Specification

/**
 * String-level scrubbing contract for {@link ReferenceDumpScrubber}: the path and
 * session-id replacement that runs before the JSON deny-list pruning. Split out of
 * {@code ReferenceDumpScrubberSpec} (process-invariants file-size limit). Runs under
 * {@code check} from {@code adapter.agent} (D2). Implements FR5 (workspace path and
 * session-id scrub stay unchanged) and FR7 (collapse machine-/user-identifying temp
 * paths — {@code var/folders} hashes, {@code tmp/claude-<uid>}, and the dashed
 * project-dir encoding — while keeping opaque per-run tokens) of
 * harden-reference-dump-scrubber.
 */
class ReferenceDumpStringScrubSpec extends Specification {

    private static final ObjectMapper MAPPER = new ObjectMapper()

    def "replaces the workspace absolute path — both plain and JSON-escaped — with /workspace"() {
        given: 'a slash-bearing workspace root appearing both plainly and JSON-escaped in one line'
        String workspaceRoot = '/private/var/folders/xx/workspaceRoot123'
        String escapedRoot = '\\/private\\/var\\/folders\\/xx\\/workspaceRoot123'
        String rawLine = '{"type":"init","session_id":"sess-abc-123",' +
                '"cwd":"' + escapedRoot + '/scratch",' +
                '"note":"resolved ' + workspaceRoot + '/out"}'

        when: 'the line is scrubbed'
        String scrubbed = ReferenceDumpScrubber.scrub(rawLine, workspaceRoot, 'sess-abc-123', 'plain')

        then: 'FR5: neither the plain nor the JSON-escaped workspace path survives anywhere'
        !scrubbed.contains(workspaceRoot)
        !scrubbed.contains(escapedRoot)

        and: 'FR5: both occurrences collapsed to the /workspace placeholder'
        JsonNode tree = MAPPER.readTree(scrubbed)
        tree.get('cwd').asText() == '/workspace/scratch'
        tree.get('note').asText() == 'resolved /workspace/out'
    }

    def "replaces the real session id with the synthetic ref-session-<label>-1 placeholder"() {
        given: 'a line carrying a real CLI-issued session id'
        String rawLine = '{"type":"assistant","session_id":"01JQ7-real-session-xyz",' +
                '"message":{"note":"session 01JQ7-real-session-xyz active"}}'

        when: 'the line is scrubbed with scenario label plain'
        String scrubbed = ReferenceDumpScrubber.scrub(
                rawLine, '/private/workspace', '01JQ7-real-session-xyz', 'plain')

        then: 'FR5: the real session id is gone and replaced by the label-derived placeholder'
        !scrubbed.contains('01JQ7-real-session-xyz')

        and: 'FR5: every occurrence becomes ref-session-plain-1'
        JsonNode tree = MAPPER.readTree(scrubbed)
        tree.get('session_id').asText() == 'ref-session-plain-1'
        tree.get('message').get('note').asText() == 'session ref-session-plain-1 active'
    }

    def "collapses a stray home-directory prefix outside the workspace to /workspace-scrubbed"() {
        given: 'a line whose path is NOT the workspace root but still embeds a real username'
        String rawLine = '{"type":"assistant","session_id":"sess-abc-123",' +
                '"stray":"/Users/alice/other/file.txt","home":"/home/bob/log"}'

        when: 'the line is scrubbed with an unrelated workspace root'
        String scrubbed = ReferenceDumpScrubber.scrub(rawLine, '/private/workspace', 'sess-abc-123', 'plain')

        then: 'FR5: no real username survives via a stray absolute path'
        !scrubbed.contains('/Users/alice')
        !scrubbed.contains('/home/bob')

        and: 'FR5: each stray user/home prefix is collapsed to /workspace-scrubbed'
        JsonNode tree = MAPPER.readTree(scrubbed)
        tree.get('stray').asText() == '/workspace-scrubbed/other/file.txt'
        tree.get('home').asText() == '/workspace-scrubbed/log'
    }

    def "collapses the macOS temp root and the per-uid claude tmp dir a real recording leaves behind"() {
        given: 'a subagent output_file that survived workspace-literal scrubbing with machine paths intact'
        String rawLine = '{"type":"system","subtype":"task_notification","task_id":"a234e5c3fadc846fe",' +
                '"output_file":"/private/tmp/claude-501/-private-var-folders-nb-x7c3tw-n1s14vctxyhzx' +
                '-bp80000gn-T-spock-records-a-real-suba-0-workspaceRoot5700695530902561121/' +
                'ref-session-subagent-1/tasks/a234e5c3fadc846fe.output"}'

        when: 'the line is scrubbed with empty path/session args (the in-place refresh path, FR6)'
        String scrubbed = ReferenceDumpScrubber.scrub(rawLine, '', '', '')

        then: 'FR7/NFR-S1: neither the per-uid tmp dir, the var/folders hash, nor its dashed encoding survives'
        !scrubbed.contains('/tmp/claude-501')
        !scrubbed.contains('var-folders')
        !scrubbed.contains('x7c3tw-n1s14vctxyhzx-bp80000gn')

        and: 'FR7: both machine prefixes collapse to the /workspace-scrubbed placeholder'
        JsonNode tree = MAPPER.readTree(scrubbed)
        tree.get('output_file').asText() ==
                '/workspace-scrubbed/-workspace-scrubbed/ref-session-subagent-1/tasks/a234e5c3fadc846fe.output'

        and: 'NG3: the opaque per-run agent id is deliberately kept as representative CLI output'
        tree.get('task_id').asText() == 'a234e5c3fadc846fe'
        tree.get('output_file').asText().endsWith('a234e5c3fadc846fe.output')
    }

    def "collapses the dashed project-dir encoding embedded in a memory_paths value"() {
        given: 'an init memory_paths.auto whose leading path is already /workspace-scrubbed but embeds the dashed temp dir'
        String rawLine = '{"type":"system","subtype":"init",' +
                '"memory_paths":{"auto":"/workspace-scrubbed/.claude/projects/' +
                '-private-var-folders-nb-x7c3tw-n1s14vctxyhzx-bp80000gn-T-spock-records-a-real-plai' +
                '-0-workspaceRoot7560614136072070791/memory/"}}'

        when: 'the line is scrubbed'
        String scrubbed = ReferenceDumpScrubber.scrub(rawLine, '', '', '')

        then: 'FR7: the dashed var-folders segment (hash + random workspaceRoot suffix) is gone'
        !scrubbed.contains('var-folders')
        !scrubbed.contains('workspaceRoot7560614136072070791')

        and: 'FR7: the segment collapses to -workspace-scrubbed, keeping the surrounding path shape'
        JsonNode tree = MAPPER.readTree(scrubbed)
        tree.get('memory_paths').get('auto').asText() ==
                '/workspace-scrubbed/.claude/projects/-workspace-scrubbed/memory/'
    }

    def "temp-path collapsing is idempotent — a second pass changes nothing"() {
        given: 'a line carrying both a slash-form and a dashed-form machine temp path'
        String rawLine = '{"type":"system","subtype":"init",' +
                '"cwd":"/private/var/folders/nb/x7c3twhashvalue1234/T/scratch",' +
                '"memory_paths":{"auto":"/tmp/claude-501/-private-var-folders-nb-x7c3tw-0-workspaceRoot99/memory/"}}'

        when: 'the line is scrubbed once, then the scrubbed output is scrubbed again'
        String once = ReferenceDumpScrubber.scrub(rawLine, '', '', '')
        String twice = ReferenceDumpScrubber.scrub(once, '', '', '')

        then: 'FR7/NFR-R1: no machine path survives the first pass'
        !once.contains('var-folders')
        !once.contains('var/folders')
        !once.contains('/tmp/claude-501')

        and: 'FR7/NFR-R1: the second pass reproduces the first byte-for-byte'
        twice == once
    }
}
