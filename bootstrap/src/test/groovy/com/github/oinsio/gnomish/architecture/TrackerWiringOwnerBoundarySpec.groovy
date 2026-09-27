package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR4, NFR-S1 of collapse-composition-roots, single-owner table ({@code TrackerWiring} row): the
 * credential seam and the tracker-resolution steps have one owner in the application layer, and a
 * second holder or a second resolution site is what the parameter types cannot see.
 *
 * <ul>
 *   <li>{@code SecretsProvider} as a field or parameter — declared only where secrets are used, not
 *       relayed: {@code TrackerWiring} (the tracker side), {@code CheckEquipment} and the
 *       provider-dispatching check client (the check-provider side, which is not this owner's
 *       concern), and the configuration that binds the bean. Every other application class reaches
 *       a tracker through the wiring and can neither obtain the seam nor hand it on.
 *   <li>{@code EpochRecordingTracker} — built only by {@code TrackerWiring.resolveTracker}, the one
 *       funnel a claiming command resolves through (FR4 of fix-claim-epoch-fence); a second
 *       construction is a tracker whose book may not be the bundle's.
 *   <li>{@code TrustedTierStartup.bind} — called only by {@code TrackerWiring.bindStartupLaw}, so
 *       every command binds its startup law with the same registry and source it resolves with.
 * </ul>
 *
 * <p>Each allowlist is also checked for staleness: every listed file must really carry the
 * declaration or the call, so a moved owner fails here instead of leaving a dead entry behind.
 * Only {@code :application} and {@code :bootstrap} are scanned: the plugin API and the adapters
 * take the seam as a method parameter by contract, which is the reach the design keeps.
 */
class TrackerWiringOwnerBoundarySpec extends Specification {

    private static final String APP = 'application/src/main/java/com/github/oinsio/gnomish/'
    private static final String BOOT = 'bootstrap/src/main/java/com/github/oinsio/gnomish/'

    /** Marker → the production files (of the two composition modules) allowed to carry it. */
    private static final Map<String, List<String>> OWNERS = [
        'SecretsProvider declaration': [
            APP + 'app/TrackerWiring.java',
            BOOT + 'app/CheckEquipment.java',
            BOOT + 'app/ManualRunConfiguration.java',
            BOOT + 'adapter/check/ProviderDispatchingExternalCheckClient.java',
        ],
        'new EpochRecordingTracker(': [
            APP + 'app/TrackerWiring.java',
        ],
        'TrustedTierStartup.bind(': [
            APP + 'app/TrackerWiring.java',
        ],
    ]

    // FR4, NFR-S1: a declaration or call outside the allowlist is a second owner.
    def "NFR-S1: only the named owners carry '#marker' in the composition modules"() {
        given: 'every production source of the two composition modules, comments stripped'
        def sources = RepoSourceTree.productionSources { String path ->
            path.startsWith('application/') || path.startsWith('bootstrap/')
        }

        expect: 'the scan really reached both modules'
        sources.any { RepoSourceTree.relative(it).startsWith('application/') }
        sources.any { RepoSourceTree.relative(it).startsWith('bootstrap/') }

        when:
        def carrying = sources.findAll {
            carries(marker, RepoSourceTree.code(it))
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then: 'nothing outside the allowlist carries it, and every allowlisted file does'
        carrying == OWNERS[marker].sort()

        where:
        marker << OWNERS.keySet().toList()
    }

    // Over a clean tree the scan finds nothing but the owners, so only seeded sources tell a
    //     working detector from a broken one; a comment or an import must not count.
    def "the detector finds a seeded #shape and leaves the rest alone"() {
        expect:
        carries(marker, source) == detected

        where:
        shape | marker | source || detected
        'field' | 'SecretsProvider declaration' | '    private final SecretsProvider secrets;' || true
        'parameter' | 'SecretsProvider declaration' | '            SecretsProvider secretsProvider,' || true
        'import only' | 'SecretsProvider declaration' | 'import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;' || false
        'javadoc mention' | 'SecretsProvider declaration' | ' * @param secrets the SecretsProvider seam' || false
        'a longer type name' | 'SecretsProvider declaration' | '    MapSecretsProvider secrets = MapSecretsProvider.NONE;' || false
        'second funnel' | 'new EpochRecordingTracker(' | '        return new EpochRecordingTracker(live, book);' || true
        'commented funnel' | 'new EpochRecordingTracker(' | '        // never new EpochRecordingTracker( here' || false
        'second bind' | 'TrustedTierStartup.bind(' | '        var law = TrustedTierStartup.bind(dir, git, source, registry);' || true
        'the definition' | 'TrustedTierStartup.bind(' | '    static StartupLaw bind(' || false
    }

    /** Whether this source carries {@code marker}, outside comments and imports. */
    private static boolean carries(String marker, String source) {
        source.readLines().any { line ->
            def code = RepoSourceTree.codeOnly(line)
            if (code.trim().startsWith('import ')) {
                return false
            }
            marker == 'SecretsProvider declaration'
                    ? (code =~ /(^|[\s(,])SecretsProvider\s+\w+\s*[;,)=]/).find()
                    : code.contains(marker)
        }
    }
}
