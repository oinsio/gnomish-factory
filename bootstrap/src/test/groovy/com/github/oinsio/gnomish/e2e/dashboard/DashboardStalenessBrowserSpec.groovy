package com.github.oinsio.gnomish.e2e.dashboard

import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import java.time.Duration
import spock.lang.IgnoreIf
import spock.lang.Timeout

/**
 * The watch page's freshness strip, run by the page's own script in a browser (requirement "Watch
 * page detects its own staleness with an inline script" of {@code observability/dashboard-page};
 * finding F93, design D15): the page the production renderer wrote bakes {@code generatedAt} and a
 * 30-second stale threshold, and the script re-reads the page clock every second. The spec moves
 * that clock; nothing reloads the page.
 *
 * <p>Implements FR14 of tighten-spec-contract (design D15); FR3 of redesign-dashboard.
 */
@Timeout(300)
@IgnoreIf(value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unreachable; Docker is a dev/CI prerequisite of the Docker-dependent E2E layer (.claude/rules/testing.md)')
class DashboardStalenessBrowserSpec extends DashboardBrowserSpecification {

    // Scenario: observability/dashboard-page / Fresh page shows no banner
    def "FR3: within the stale window the strip is in the ok palette, its age ticking up, no card dimmed"() {
        given: 'the page open twelve seconds after it was rendered, inside the 30-second window'
        openAt(Duration.ofSeconds(12))

        expect: 'the strip shows the ok palette and the age the clock gives'
        TICKS.eventually {
            def strip = strip()
            assert strip.state == 'fresh'
            assert strip.text == 'data is fresh · updated 12 s ago'
            assert strip.background == token('--ok-bg')
        }

        and: 'the age counts up with the clock, one second per tick'
        (13..15).each { int second ->
            clockAt(Duration.ofSeconds(second))
            TICKS.eventually {
                assert strip().text == "data is fresh · updated ${second} s ago".toString()
            }
        }

        and: 'no card and no footnote is dimmed'
        browser.script('return document.body.classList.contains("is-stale")') == false
        dimmedOpacities().every { it == 1.0d }
    }

    // Scenario: observability/dashboard-page / Dead renderer banners the page
    def "FR3: past the stale threshold the strip turns to the alarm palette without a reload, its counter climbing, every card readable"() {
        given: 'the page open while fresh, and its meta-refresh never firing: the renderer is dead'
        openAt(Duration.ofSeconds(5))
        browser.holdMetaRefresh()
        browser.script('window.__gfSameDocument = true')
        TICKS.eventually { assert strip().state == 'fresh' }

        when: 'the page clock passes the 30-second threshold'
        clockAt(Duration.ofSeconds(31))

        then: 'within a tick the strip is in the alarm palette and says how long the renderer has been silent'
        TICKS.eventually {
            def strip = strip()
            assert strip.state == 'stale'
            assert strip.background == token('--bad-bg')
            assert strip.text == 'view is stale — renderer silent for 31s'
        }

        when: 'the clock keeps running'
        clockAt(Duration.ofSeconds(47))

        then: 'the counter climbs with it'
        TICKS.eventually {
            assert strip().text == 'view is stale — renderer silent for 47s'
        }

        when:
        clockAt(Duration.ofMinutes(2).plusSeconds(5))

        then:
        TICKS.eventually {
            assert strip().text == 'view is stale — renderer silent for 2m 5s'
        }

        and: 'it was the script that degraded the page: the document was never reloaded'
        browser.script('return window.__gfSameDocument === true') == true

        and: 'every card is dimmed, not hidden: rendered, uncovered at its centre, its name still readable'
        def cards = cardReadability()
        cards.size() == 5
        cards.every { it.opacity> 0.0d && it.opacity < 1.0d }
        cards.every { it.uncovered }
        cards.every { it.nameContrast >= 3.0d }
    }

    private List<Double> dimmedOpacities() {
        browser.script('''
            return Array.prototype.map.call(document.querySelectorAll(".card, .footnote"), function (el) {
              return Number(getComputedStyle(el).opacity);
            });''').collect { it as double }
    }

    /**
     * Per card: its computed opacity; whether the element painted at its centre is the card or
     * inside it (nothing covers it); and the contrast its name — the card title, or the status
     * line's state — keeps through the dimming. Readable is WCAG's 3:1 floor for text read at
     * large scale: the dimming is the designed stale signal, so the body text is not held to the
     * 4.5:1 of a fresh page.
     */
    private List<Map> cardReadability() {
        browser.legibility('''Array.prototype.map.call(document.querySelectorAll(".card"), function (card) {
              card.scrollIntoView({block: "center"});
              var box = card.getBoundingClientRect();
              var hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
              var name = card.querySelector(".card__title, .status__state");
              return {
                opacity: Number(getComputedStyle(card).opacity),
                uncovered: hit !== null && card.contains(hit),
                nameContrast: name === null ? 0 : gfLegibility.contrast(name)
              };
            })''').collect { it as Map }
    }
}
