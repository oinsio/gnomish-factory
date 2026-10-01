package com.github.oinsio.gnomish.config

import static com.github.oinsio.gnomish.operatorconfig.Level.ANY
import static com.github.oinsio.gnomish.operatorconfig.Level.HOST
import static com.github.oinsio.gnomish.operatorconfig.Level.PROJECT
import static com.github.oinsio.gnomish.operatorconfig.Level.SANDBOX_BOUNDARY

import com.github.oinsio.gnomish.config.ConfigLevelsFixtures.Inner
import com.github.oinsio.gnomish.config.ConfigLevelsFixtures.NotARecord
import com.github.oinsio.gnomish.config.ConfigLevelsFixtures.NotProperties
import com.github.oinsio.gnomish.config.ConfigLevelsFixtures.Renamed
import com.github.oinsio.gnomish.config.ConfigLevelsFixtures.Root
import com.github.oinsio.gnomish.config.ConfigLevelsFixtures.Unlevelled
import java.time.Duration
import spock.lang.Specification

/**
 * The key → level table {@link ConfigLevels} derives from the {@code @ConfigLevel} annotations of a
 * fixture record set (design D4): Spring's dashed names, {@code @Name} overrides, nested records,
 * subtrees for maps and lists, relaxed lookup, and the refusal of a record it cannot place. The
 * fixture records are {@link ConfigLevelsFixtures}, declared in Java.
 *
 * <p>Implements FR4, FR6 of add-project-registry.
 */
class ConfigLevelsSpec extends Specification {

    def levels = ConfigLevels.of([Root, Renamed])

    // FR6: every leaf component is one key, dashed as Spring binds, nested records descended into
    def "the table lists every key with its level, ordered by name"() {
        expect:
        levels.keys().collect {
            [
                it.toString(),
                it.level(),
                it.subtree()
            ]
        } == [
            [
                'fixture.bindings.default',
                SANDBOX_BOUNDARY,
                false
            ],
            [
                'fixture.bindings.extra',
                SANDBOX_BOUNDARY,
                true
            ],
            [
                'fixture.check',
                PROJECT,
                true
            ],
            [
                'fixture.git-network-timeout',
                ANY,
                false
            ],
            [
                'fixture.inner.allowlist',
                SANDBOX_BOUNDARY,
                true
            ],
            [
                'fixture.inner.docker-runtime',
                HOST,
                false
            ],
        ]
    }

    // FR6: a source's spelling is matched as Spring binds it; subtrees cover the keys below
    def "a property is found under relaxed names and below a subtree"() {
        expect:
        levels.find(property).map { it.toString() }.orElse(null) == key

        where:
        property || key
        'fixture.git-network-timeout' || 'fixture.git-network-timeout'
        'fixture.gitNetworkTimeout' || 'fixture.git-network-timeout'
        'fixture.git_network_timeout' || 'fixture.git-network-timeout'
        'fixture.check.github.repo' || 'fixture.check'
        'fixture.inner.allowlist[0]' || 'fixture.inner.allowlist'
        'fixture.bindings.default' || 'fixture.bindings.default'
        'fixture.git-network-timeout.extra' || null
        'fixture.inner' || null
        'fixture.misspelled' || null
    }

    // FR4: the built-in default is read from a bound record along the key's path
    def "a key's value is read from a bound record, null when a record above it is unset"() {
        given:
        def bound = new Root(Duration.ofMinutes(5), [:], new Inner('runc', ['a']))
        def unset = new Root(null, [:], null)

        expect:
        levels.find('fixture.git-network-timeout').get().valueIn(bound) == Duration.ofMinutes(5)
        levels.find('fixture.inner.docker-runtime').get().valueIn(bound) == 'runc'
        levels.find('fixture.inner.docker-runtime').get().valueIn(unset) == null
    }

    // M2, FR6: a key without a level cannot be placed
    def "a component without a level is refused naming the key"() {
        when:
        ConfigLevels.of([Unlevelled])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "fixture.bad.missing (${Unlevelled.name}.missing) declares no @ConfigLevel"
    }

    // FR6: only @ConfigurationProperties records carry keys
    def "a class that is not a @ConfigurationProperties record is refused"() {
        when:
        ConfigLevels.of([type])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "${type.name} is not a @ConfigurationProperties record"

        where:
        type << [NotProperties, NotARecord]
    }

    // FR6: the application's own table covers its registered records
    def "the application table places the documented keys at their design D5 levels"() {
        given:
        def application = ConfigLevels.application()

        expect:
        application.find(property).get().level() == level

        where:
        property || level
        'factory.docker-command-timeout' || HOST
        'factory.sandbox.project-id' || PROJECT
        'factory.check.github.repo' || PROJECT
        'factory.serve.slots' || ANY
        'factory.tracker.abort-backoff-base' || ANY
        'factory.sandbox.limits.cpus' || ANY
        'factory.bindings.default' || SANDBOX_BOUNDARY
        'factory.bindings.stages.build' || SANDBOX_BOUNDARY
        'factory.sandbox.egress-allowlist[0]' || SANDBOX_BOUNDARY
    }

    // FR7: a FACTORY_* variable is named back as the key it spells, so the refusal can quote it
    def "FR7: an environment variable maps to the key it spells: #variable"() {
        expect:
        ConfigLevels.application().propertyOf(variable) == property

        where:
        variable || property
        'FACTORY_SERVE_SLOTS' || 'factory.serve.slots'
        'FACTORY_GIT_NETWORK_TIMEOUT' || 'factory.git-network-timeout'
        'factory_instance_name' || 'factory.instance-name'
        'FACTORY_CHECK_GITHUB_REPO' || 'factory.check.github.repo'
        'FACTORY_SANDBOX_EGRESS_ALLOWLIST_0' || 'factory.sandbox.egress-allowlist.0'
        'FACTORY_NO_SUCH_KEY' || 'factory.no.such.key'
    }
}
