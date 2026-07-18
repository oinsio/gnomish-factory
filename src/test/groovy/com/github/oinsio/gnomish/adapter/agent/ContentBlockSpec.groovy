package com.github.oinsio.gnomish.adapter.agent

import spock.lang.Specification

/**
 * ContentBlock: the sealed text/tool_use/tool_result shapes of a message's
 * content array (design D3). Covers construction, defensive copy of {@code
 * ToolUse.input}, and the blank/null validation each variant enforces.
 * Implements FR4, D3 of add-agent-executor.
 */
class ContentBlockSpec extends Specification {

    // FR4, D3: a Text block exposes its text as constructed
    def "Text exposes its text as constructed"() {
        expect:
        new ContentBlock.Text('hello').text() == 'hello'
    }

    // FR4, D3: a ToolUse block exposes id, name and input as constructed
    def "ToolUse exposes id, name and input as constructed"() {
        given:
        def input = [file_path: 'a.txt']

        when:
        def block = new ContentBlock.ToolUse('toolu_1', 'Write', input)

        then:
        block.id() == 'toolu_1'
        block.name() == 'Write'
        block.input() == input
    }

    // FR4, D3: ToolUse.input is defensively copied — later source mutation cannot leak in
    def "ToolUse defensively copies input from the source map"() {
        given:
        def source = [file_path: 'a.txt']

        when:
        def block = new ContentBlock.ToolUse('toolu_1', 'Write', source)
        source.put('extra', 'value')

        then:
        block.input() == [file_path: 'a.txt']
    }

    // FR4, D3: a ToolResult block exposes toolUseId and content as constructed
    def "ToolResult exposes toolUseId and content as constructed"() {
        expect:
        new ContentBlock.ToolResult('toolu_1', 'file written').toolUseId() == 'toolu_1'
        new ContentBlock.ToolResult('toolu_1', 'file written').content() == 'file written'
    }

    // FR4: a blank ToolUse id is rejected
    def "rejects a blank ToolUse id"() {
        when:
        new ContentBlock.ToolUse('', 'Write', [:])

        then:
        thrown(IllegalArgumentException)
    }

    // FR4: a blank ToolResult toolUseId is rejected
    def "rejects a blank ToolResult toolUseId"() {
        when:
        new ContentBlock.ToolResult('  ', 'content')

        then:
        thrown(IllegalArgumentException)
    }
}
