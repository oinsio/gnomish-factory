package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * Design D17 of add-sandbox-base-image, the {@code ServePreconditions} row: the startup assessment
 * calls the checks a slot runs, through their ports, and spells none of them itself — a {@code git
 * rev-parse --show-ref-format} of its own, a {@code docker info}, a {@code git --version} would be
 * a second owner of one fact, the pair {@code manual-sync-pairs.md} forbids. This scan keeps every
 * spelling out of the serve package and the three startup classes; the checks' own owners, in the
 * adapters, are outside the scanned set.
 *
 * <p>FR13, NFR-R3 of add-sandbox-base-image.
 */
class ServePreconditionSpellingSpec extends Specification {

    private static final String APP = 'application/src/main/java/com/github/oinsio/gnomish/app/'
    private static final String SERVE = APP + 'serve/'
    private static final List<String> STARTUP = [
        APP + 'ServeCommand.java',
        APP + 'ServeStartupGates.java',
        APP + 'ServePreconditions.java',
    ]

    private static final Pattern SPELLING = Pattern.compile(/show-ref-format|\bdocker\s+info\b|"info"|\bgit\s+--version\b|"--version"/)

    def "no file under app/serve and no startup class spells a precondition check"() {
        given: 'the serve package and the startup classes, comments removed'
        def sources = RepoSourceTree.productionSources().findAll {
            def path = RepoSourceTree.relative(it)
            path.startsWith(SERVE) || STARTUP.contains(path)
        }
        def reached = sources.collect { RepoSourceTree.relative(it) }

        expect: 'the scan reached the package and every startup class, so a move cannot empty the gate'
        reached.count { it.startsWith(SERVE) } > 40
        reached.containsAll(STARTUP)

        and: 'no file spells a check'
        sources.findAll { SPELLING.matcher(RepoSourceTree.code(it)).find() }
        .collect { RepoSourceTree.relative(it) } == []
    }

    // The detector is the gate: a seeded spelling is found, the port calls are not
    def "the detector: #shape"() {
        expect:
        SPELLING.matcher(RepoSourceTree.codeOnly(line)).find() == detected

        where:
        shape | line || detected
        'the ref-format read' | 'runner.run(List.of("rev-parse", "--show-ref-format"));' || true
        'a docker info argv' | 'docker.run(List.of("info"));' || true
        'a docker info command line' | 'String probe = "docker info";' || true
        'a git version argv' | 'runner.run(List.of("--version"));' || true
        'the ref-format port' | 'branches.requireRefFormat(clone.clonePath());' || false
        'the readiness port' | 'imageReadiness.assess(image);' || false
        'a comment' | '// docker info is the probe' || false
    }
}
