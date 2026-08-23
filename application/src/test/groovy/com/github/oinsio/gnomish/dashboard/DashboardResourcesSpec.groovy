package com.github.oinsio.gnomish.dashboard

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Verifies the page's stylesheet and script are loaded from the classpath
 * once and validated before a single page is rendered (task 1.2 of
 * redesign-dashboard): a missing or blank resource fails fast rather than
 * producing an unstyled or scriptless page, an unreadable one keeps its
 * cause, and content that would terminate the inline {@code <script>} block
 * early is refused at load time rather than silently half-parsing in the
 * browser. Every refusal names the file to fix.
 *
 * FR10, NFR-R1 of redesign-dashboard (design D1, D2).
 */
class DashboardResourcesSpec extends Specification {

    def "both prescribed resources are present on the classpath and non-empty"() {
        when:
        def resources = DashboardResources.load()

        then:
        resources.css().contains(':root')
        resources.js().contains('freshness')
    }

    def "a missing classpath resource fails fast instead of rendering an unstyled page"() {
        when:
        DashboardResources.read('/dashboard/does-not-exist.css')

        then:
        def error = thrown(IllegalStateException)
        error.message.contains('/dashboard/does-not-exist.css')

        and: 'the refusal names where to restore it'
        error.message.contains('application/src/main/resources/dashboard/does-not-exist.css')
    }

    // NFR-R1: a present-but-empty resource reaches the very outcome the missing-resource
    //         guard exists to prevent — an unstyled or scriptless page — so it is refused
    //         at load time on the same terms.
    @Unroll
    def "a blank resource is refused rather than rendering an unstyled page: #description"() {
        when:
        new DashboardResources(css, js)

        then:
        def error = thrown(IllegalStateException)
        error.message.contains(path)
        error.message.contains('is empty')

        and: 'the refusal names where to fix it'
        error.message.contains('application/src/main/resources' + path)

        where:
        description | css | js | path
        'an empty stylesheet' | '' | 'var a = 1;' | '/dashboard/dashboard.css'
        'a whitespace-only stylesheet' | ' \n\t ' | 'var a = 1;' | '/dashboard/dashboard.css'
        'an empty script' | 'body{}' | '' | '/dashboard/dashboard.js'
        'a whitespace-only script' | 'body{}' | '\n  \n' | '/dashboard/dashboard.js'
    }

    // NFR-R1: a resource that opens but cannot be read through is an infrastructure
    //         failure, not a validation one — it keeps its cause instead of being
    //         reported as a missing file.
    def "a resource that fails mid-read is reported as unreadable, with its cause kept"() {
        given:
        def torn = new InputStream() {
                    @Override
                    int read() throws IOException {
                        throw new IOException('the jar went away')
                    }
                }

        when:
        DashboardResources.read('/dashboard/dashboard.css', {
            torn
        } as DashboardResources.ResourceSource)

        then:
        def error = thrown(UncheckedIOException)
        error.message.contains('/dashboard/dashboard.css')
        error.cause.message == 'the jar went away'
    }

    @Unroll
    def "content that would terminate its own inline block early is refused: #description"() {
        when:
        new DashboardResources(css, js)

        then:
        def error = thrown(IllegalStateException)
        error.message.contains(path)

        where:
        description | css | js | path
        'lowercase in the script' | 'body{}' | 'var a = "</script>";' | '/dashboard/dashboard.js'
        'uppercase in the script' | 'body{}' | 'var a = "</SCRIPT>";' | '/dashboard/dashboard.js'
        'whitespace between the slash and the name' | 'body{}' | 'var a = "</ script>";' | '/dashboard/dashboard.js'
        // NFR-R1: the stylesheet is inlined into <style>, whose only terminator is </style
        'lowercase in the stylesheet' | 'body{content:"</style>"}' | 'var a = 1;' | '/dashboard/dashboard.css'
        'uppercase in the stylesheet' | 'body{content:"</STYLE>"}' | 'var a = 1;' | '/dashboard/dashboard.css'
        'whitespace in the stylesheet' | 'body{content:"</ style>"}' | 'var a = 1;' | '/dashboard/dashboard.css'
    }

    // NFR-R1: each block is guarded by its OWN terminator — a stylesheet may
    // name </script> harmlessly, since <style> raw text ends only at </style.
    @Unroll
    def "the other block's terminator is not the guard: #description"() {
        when:
        def resources = new DashboardResources(css, js)

        then:
        resources.css() == css
        resources.js() == js

        where:
        description | css | js
        'a stylesheet naming the script terminator' | 'body{content:"</script>"}' | 'var a = 1;'
        'a script naming the style terminator' | 'body{}' | 'var a = "</style>";'
    }

    def "ordinary content carrying the word script is accepted"() {
        when:
        def resources = new DashboardResources('/* script styling */', 'var script = 1;')

        then:
        resources.js() == 'var script = 1;'
    }

    // FR3: the stale threshold travels as data-stale-after on the body, but the
    //      script keeps a fallback for a body missing the attribute — pin the
    //      fallback to the shipped cadence so the two cannot drift apart.
    def "the script's stale-threshold fallback is three times the watch loop's render cadence"() {
        given:
        def matcher = DashboardResources.load().js() =~ /staleAfter\) \|\| (\d+)/
        matcher.find()
        def fallback = matcher.group(1) as long

        expect:
        fallback == DashboardWatchLoop.RENDER_CADENCE.toMillis() * 3
    }

    // NFR-O1 of redesign-dashboard: both themes come from the token blocks alone —
    //      a colour literal below them would silently ignore the dark scheme.
    def "no colour literal appears outside the stylesheet's token blocks"() {
        given: 'the stylesheet with both token blocks (:root and its dark override) removed'
        def rules = DashboardResources.load().css().replaceAll(/(?s):root \{.*?\n\s*}/, '')

        expect:
        !(rules =~ /#[0-9a-fA-F]{3,8}\b/)
    }

    // FR10: whatever the resources hold, the rendered page must stay parseable as one file.
    def "the shipped resources themselves carry no early script terminator"() {
        given:
        def resources = DashboardResources.load()

        expect:
        !(resources.css() =~ /(?i)<\/\s*style/)
        !(resources.js() =~ /(?i)<\/\s*script/)
    }
}
