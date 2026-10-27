package com.github.oinsio.gnomish.doctor.json

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

/**
 * FR5 of add-doctor-command (design D5): the {@code --json} document is {@code version} and the
 * results in report order, each with {@code id}, {@code status}, {@code detail} and {@code fix} —
 * a missing fix rendered as JSON {@code null}, never omitted — and the text exactly as handed in.
 */
class DoctorReportJsonMapperSpec extends Specification {

    def mapper = new DoctorReportJsonMapper()

    def "FR5: the document carries version 1 and every result in order, with a null fix kept as null"() {
        given:
        def checks = [
            new DoctorCheckDto('git-floor', 'fail', 'git 2.55.2 is below the floor', 'upgrade git'),
            new DoctorCheckDto('docker', 'ok', 'reachable', null),
        ]

        when:
        def tree = new ObjectMapper().readTree(mapper.serialize(checks))

        then:
        tree.fieldNames().toList() == ['version', 'checks']
        tree.get('version').intValue() == DoctorReportJsonMapper.VERSION
        DoctorReportJsonMapper.VERSION == 1
        tree.get('checks').size() == 2
        tree.get('checks').get(0).fieldNames().toList() == [
            'id',
            'status',
            'detail',
            'fix'
        ]
        tree.get('checks').get(0).get('id').textValue() == 'git-floor'
        tree.get('checks').get(0).get('status').textValue() == 'fail'
        tree.get('checks').get(0).get('detail').textValue() == 'git 2.55.2 is below the floor'
        tree.get('checks').get(0).get('fix').textValue() == 'upgrade git'
        tree.get('checks').get(1).has('fix')
        tree.get('checks').get(1).get('fix').isNull()
    }

    def "FR5: a detail with quotes, a line break and a control character stays one valid document"() {
        given:
        def detail = 'said "no"\nthen ^[ and \u0007'

        when:
        def tree = new ObjectMapper().readTree(mapper.serialize([
            new DoctorCheckDto('pipeline', 'fail', detail, null)
        ]))

        then:
        tree.get('checks').get(0).get('detail').textValue() == detail
    }

    def "FR5: a run with no result is a document with an empty list"() {
        expect:
        new ObjectMapper().readTree(mapper.serialize([])).get('checks').size() == 0
    }
}
