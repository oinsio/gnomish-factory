package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import spock.lang.Specification

/**
 * FR9 of add-tracker-port: turning one explicit-mode {@code <ref>} token into a {@link TaskRef}.
 * A short ref ({@code 42}, {@code #42}) is expanded by the tracker adapter registered for the
 * pipeline's tracker type, because only that adapter knows what repository a bare issue number
 * belongs to; anything else is already canonical and is wrapped verbatim, with no registry lookup
 * at all.
 *
 * <p>Added by task 8.7 of split-into-modules (design D13(c)).
 */
class TakeRefResolutionSpec extends Specification {

    private static final TrackerConfig CONFIG = new TrackerConfig('github', 3)

    // FR9: a canonical ref is nobody's business but its own — it is wrapped as-is, and the adapter
    // registry is never consulted, so an unconfigured tracker type cannot break it.
    def "wraps an already-canonical ref verbatim without consulting the registry"() {
        when:
        def ref = TakeRefResolution.resolve('github:owner/repo#42', CONFIG, [:])

        then:
        ref == new TaskRef('github:owner/repo#42')
    }

    // FR9: the short-ref shapes the parser accepts are expanded by the registered adapter, which is
    // what supplies the repository coordinates a bare number lacks.
    def "expands a short ref through the adapter registered for the pipeline's tracker type"() {
        given:
        def expanded = new TaskRef('github:owner/repo#42')
        def factory = Mock(TrackerAdapterFactory)

        when:
        def ref = TakeRefResolution.resolve(shortRef, CONFIG, ['github': factory])

        then:
        1 * factory.expandRef(CONFIG, shortRef) >> expanded
        ref == expanded

        where:
        shortRef << ['42', '#42']
    }

    // FR9: only the registered type's factory is asked — a registry holding other vendors must not
    // be used to expand a ref for the type this pipeline actually declares.
    def "asks only the factory registered for the declared type"() {
        given:
        def github = Mock(TrackerAdapterFactory)
        def jira = Mock(TrackerAdapterFactory)

        when:
        TakeRefResolution.resolve('42', CONFIG, ['github': github, 'jira': jira])

        then:
        1 * github.expandRef(CONFIG, '42') >> new TaskRef('github:owner/repo#42')
        0 * jira._
    }

    // FR9: a short ref with no adapter for the declared type cannot be expanded at all, and that is
    // an operator-facing configuration error — it names the ref, the unknown type, and what IS
    // supported, rather than failing with a null factory.
    def "refuses a short ref when no adapter is registered for the declared type"() {
        when:
        TakeRefResolution.resolve('42', CONFIG, [:])

        then:
        def ex = thrown(UsageException)
        ex.message.contains("cannot expand short ref '42'")
        ex.message.contains("unknown tracker type 'github'")
    }
}
