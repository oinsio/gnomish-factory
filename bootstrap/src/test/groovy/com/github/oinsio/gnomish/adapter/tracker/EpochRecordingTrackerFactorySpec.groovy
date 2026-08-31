package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.lease.EpochRecordingTracker
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import spock.lang.Specification

/**
 * FR13 of harden-task-branch-contract: the composition root wraps every discovered provider so the
 * trackers it builds keep the instance's claim-epoch book current — and changes nothing else about
 * the provider, which still decides its own type, ref expansion, validation, and credential names.
 */
class EpochRecordingTrackerFactorySpec extends Specification {

    private static final TrackerConfig CONFIG = new TrackerConfig('demo', 3)
    private static final TaskRef REF = new TaskRef('PROJ-1')

    private TrackerAdapterFactory delegate = Mock()
    private ClaimEpochBook book = new ClaimEpochBook()
    private TrackerAdapterFactory wrapped = new EpochRecordingTrackerFactory(delegate, book)

    // FR13: the tracker a wrapped provider builds records its tenures
    def "the tracker it builds is epoch-recording, over the provider's own tracker"() {
        given:
        def live = Mock(Tracker)
        delegate.create(MapSecretsProvider.NONE, CONFIG, 'gnomish-a-1') >> live

        when:
        def built = wrapped.create(MapSecretsProvider.NONE, CONFIG, 'gnomish-a-1')

        then:
        built instanceof EpochRecordingTracker
    }

    // FR13: everything except construction is the provider's decision, passed through untouched
    def "every other question is answered by the provider it wraps"() {
        given:
        def validator = Mock(TrackerSubsectionValidator)

        when:
        def type = wrapped.type()
        def expanded = wrapped.expandRef(CONFIG, '1')
        def refusal = wrapped.refuseForeignRef(MapSecretsProvider.NONE, CONFIG, REF)
        def subsection = wrapped.subsectionValidator()
        def credentials = wrapped.credentialEnvVars(CONFIG)

        then:
        1 * delegate.type() >> 'demo'
        1 * delegate.expandRef(CONFIG, '1') >> REF
        1 * delegate.refuseForeignRef(MapSecretsProvider.NONE, CONFIG, REF) >> Optional.of('foreign')
        1 * delegate.subsectionValidator() >> Optional.of(validator)
        1 * delegate.credentialEnvVars(CONFIG) >> ['DEMO_TOKEN']

        and:
        type == 'demo'
        expanded == REF
        refusal == Optional.of('foreign')
        subsection == Optional.of(validator)
        credentials == ['DEMO_TOKEN']
    }
}
