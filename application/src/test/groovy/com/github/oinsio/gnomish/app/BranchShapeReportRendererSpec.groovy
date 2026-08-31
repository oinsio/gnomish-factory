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
        new BranchShape.Created() || 'Created'
    }

    // FR16, UX4: Bare used to sit in the table above, and that was the defect. It is the shape an
    //     operator meets most often on a branch nothing has happened on yet, and "Shape: Bare" tells
    //     them nothing — it reads like a fault report for what is in fact the ordinary empty state.
    //     It is not a quarantine shape, so the old "quarantine shapes only" rule gave it no
    //     diagnosis; the rule is now "shapes whose name is not the whole answer", which is what
    //     BranchShapeDiagnosis.diagnosisFor owns for every renderer at once.
    def "the Bare shape explains itself rather than reporting a bare name"() {
        when:
        def text = renderer.renderText('PROJ-5', new BranchShape.Bare())

        then:
        text.readLines() == [
            'Task: PROJ-5',
            'Shape: Bare',
            'Diagnosis: a task branch carrying no STARTED commit — nothing of the task is recorded on it yet',
        ]
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
