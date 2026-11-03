package com.github.oinsio.gnomish.testfixtures.executor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * What a rendered executor request may say about tool permissions (FR11 of
 * define-executor-contract; wire-contract "Policy is not a vendor tool name"): the policy is one of
 * the three abstract kinds, with a path for {@code writeExactly} only, and no field name or text of
 * the request is a vendor tool identifier — the agent CLI's tool names and its permission flags,
 * the vocabulary the agent adapter compiles a policy into and the request must never carry.
 *
 * <p>Test fixture, not production code. The renderers' specs ({@code ProgramExecutorSpec},
 * {@code CaseJudgementSpec}) judge the document each renderer really wrote, handed over as the
 * request's JSON text so no Jackson type crosses this fixture's surface.
 */
final class RenderedPolicy {

    /** The abstract policy kinds the request schema admits. */
    static final Set<String> KINDS = [
        'readOnly',
        'writeExactly',
        'workspaceWrite'
    ] as Set

    /** The agent CLI's tool names and permission flags: what a vendor-specific policy would spell. */
    static final Set<String> VENDOR_WORDS = [
        'Read',
        'Grep',
        'Glob',
        'LS',
        'Write',
        'Edit',
        'MultiEdit',
        'NotebookEdit',
        'Bash',
        'WebFetch',
        'WebSearch',
        'Task',
        'TodoWrite',
        'allowedTools',
        'disallowedTools',
        'permissionMode',
        'permission-mode'
    ] as Set

    private static final ObjectMapper JSON = new ObjectMapper()

    private RenderedPolicy() {}

    /** Whether the policy of {@code requestJson} is an abstract kind alone, with its path exactly when it writes one. */
    static boolean abstractOnly(String requestJson) {
        JsonNode policy = JSON.readTree(requestJson).path('policy')
        String kind = policy.path('kind').asText()
        def fields = policy.fieldNames().toList() as Set
        KINDS.contains(kind) && fields == (kind == 'writeExactly' ? ['kind', 'path'] : ['kind']) as Set
    }

    /** Every field name and text value of {@code requestJson} that is a vendor tool identifier, as {@code path: word}. */
    static List<String> vendorWords(String requestJson) {
        List<String> found = []
        walk(JSON.readTree(requestJson), '$', found)
        found
    }

    private static void walk(JsonNode node, String path, List<String> found) {
        if (node.isObject()) {
            node.properties().each { entry ->
                if (VENDOR_WORDS.contains(entry.key)) {
                    found << "${path}.${entry.key}: ${entry.key}".toString()
                }
                walk(entry.value, "${path}.${entry.key}", found)
            }
        } else if (node.isArray()) {
            for (int i = 0; i <node.size(); i++) {
                walk(node.get(i), "${path}[${i}]", found)
            }
        } else if (node.isTextual()) {
            def word = (node.textValue() =~ /^([A-Za-z-]+)(\(.*\))?$/)
            if (word.matches() && VENDOR_WORDS.contains(word.group(1))) {
                found << "${path}: ${node.textValue()}".toString()
            }
        }
    }
}
