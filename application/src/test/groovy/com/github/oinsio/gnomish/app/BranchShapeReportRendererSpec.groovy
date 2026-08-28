package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.branch.BranchShape
import spock.lang.Specification

/**
 * FR16, UX4 of harden-task-branch-contract: a branch whose tip carries no report renders as its
 * shape — a diagnosis line only where the shape carries one, in text and in the {@code --json}
 * object alike.
 */
class BranchShapeReportRendererSpec extends Specification {

    def renderer = new BranchShapeReportRenderer()

    def "a shape whose name is the whole answer renders without a diagnosis line"() {
        when:
        def text = renderer.renderText('PROJ-1', shape)

        then:
        text.readLines() == [
            'Task: PROJ-1',
            'Shape: ' + label
        ]

        where:
        shape || label
        new BranchShape.Delivered() || 'Delivered'
        new BranchShape.Bare() || 'Bare'
        new BranchShape.Created() || 'Created'
    }

    def "a quarantine shape renders its diagnosis naming the file and the observed versus expected content"() {
        when:
        def text = renderer.renderText('PROJ-2', new BranchShape.UnsupportedVersion('state.json', 2, 1))

        then:
        text.readLines() == [
            'Task: PROJ-2',
            'Shape: UnsupportedVersion',
            'Diagnosis: state.json declaring version 2 where this factory supports 1',
        ]
    }

    def "the JSON object carries taskId, shape and a null diagnosis for a shape that carries none"() {
        when:
        def json = renderer.renderJson('PROJ-3', new BranchShape.Delivered())

        then:
        json.contains('"taskId" : "PROJ-3"')
        json.contains('"shape" : "Delivered"')
        json.contains('"diagnosis" : null')
    }

    def "the JSON object carries the diagnosis of a shape that refuses inspection"() {
        when:
        def json = renderer.renderJson('PROJ-4', new BranchShape.Corrupt('state.json: truncated'))

        then:
        json.contains('"shape" : "Corrupt"')
        json.contains('"diagnosis" : "corrupt content (state.json: truncated)"')
    }
}
