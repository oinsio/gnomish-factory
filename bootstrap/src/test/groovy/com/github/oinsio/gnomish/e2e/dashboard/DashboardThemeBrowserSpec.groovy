package com.github.oinsio.gnomish.e2e.dashboard

import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import java.time.Duration
import spock.lang.IgnoreIf
import spock.lang.Timeout

/**
 * The page in both colour schemes, painted by a browser (requirement "Page themes automatically from
 * design tokens" of {@code observability/dashboard-page}; finding F93, design D15). The operating
 * system's preference is emulated over CDP ({@code prefers-color-scheme}), and the page the
 * production renderer wrote is measured on the colours the browser computed.
 *
 * <p><b>Legible</b> is WCAG 2.2 level AA, on every element of the fresh page: text at least 4.5:1
 * against the background it is painted on (3:1 for large text) — success criterion 1.4.3 — and the
 * bar segments and legend swatches, the graphics a reader needs to tell a bar's mix, at least 3:1
 * against their track or card — criterion 1.4.11. The freshness strip is text on its own background,
 * so the first rule covers it. The measurement is {@code legibility.js}.
 *
 * <p>Implements FR14 of tighten-spec-contract (design D15); FR7 of redesign-dashboard.
 */
@Timeout(300)
@IgnoreIf(value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unreachable; Docker is a dev/CI prerequisite of the Docker-dependent E2E layer (.claude/rules/testing.md)')
class DashboardThemeBrowserSpec extends DashboardBrowserSpecification {

    // Scenario: observability/dashboard-page / Both themes legible
    def "FR7: with the OS in #scheme mode every text, bar segment and the freshness strip meets WCAG AA contrast"() {
        given:
        browser.emulateColorScheme(scheme)
        openAt(Duration.ofSeconds(1))

        expect: 'the page sees the emulated preference'
        browser.script('return matchMedia("(prefers-color-scheme: dark)").matches') == (scheme == 'dark')

        and: 'every element was measured: the strip, the cards, every bar of both blocks and their legend'
        def texts = browser.legibility('gfLegibility.texts()').collect {
            it as Map
        }
        def graphics = browser.legibility('gfLegibility.graphics()').collect {
            it as Map
        }
        texts.any {
            it.element == 'span' && it.text.startsWith('data is fresh')
        }
        texts.size() >= 40
        graphics.size() >= 30

        and: 'none falls below its minimum'
        below(texts) == []
        below(graphics) == []

        where:
        scheme << ['light', 'dark']
    }

    private static List<String> below(List<Map> measured) {
        measured.findAll { (it.ratio as double) < (it.required as double) }
        .collect {
            "${it.element} '${it.text}': ${String.format('%.2f', it.ratio as double)} < ${it.required}".toString()
        }
    }
}
