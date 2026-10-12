package com.github.oinsio.gnomish.e2e.dashboard

import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import java.time.Duration
import spock.lang.IgnoreIf
import spock.lang.Timeout

/**
 * The page's timestamps tick in a browser (requirement "Timestamps render relative with an absolute
 * fallback" of {@code observability/dashboard-page}; finding F93, design D15): the production renderer
 * writes each as a {@code <time>} carrying the ISO instant and the epoch, and the script rewrites the
 * text to a relative age on every tick, moving the absolute instant into {@code title} — the text a
 * pointer hovering the element reveals.
 *
 * <p>Implements FR14 of tighten-spec-contract (design D15); FR8, UX2 of redesign-dashboard.
 */
@Timeout(300)
@IgnoreIf(value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unreachable; Docker is a dev/CI prerequisite of the Docker-dependent E2E layer (.claude/rules/testing.md)')
class DashboardRelativeTimeBrowserSpec extends DashboardBrowserSpecification {

    // Scenario: observability/dashboard-page / Relative time ticks
    def "FR8: every timestamp shows a relative age that follows the clock each second, its title the full ISO instant"() {
        given: 'the page open 42 seconds after the board it shows was fetched'
        openAt(Duration.ofSeconds(42))

        expect: 'the page carries timestamps beyond the freshness strip: the board fetch, the claims'
        def initial = timestamps()
        initial.size() >= 4

        and: 'each shows its relative age, and its title is the full ISO instant of its datetime'
        TICKS.eventually {
            timestamps().each { stamp ->
                assert stamp.text ==~ /just now|\d+ (s|min|h|d) ago/
                assert stamp.title == stamp.datetime
                assert stamp.title ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z/
            }
        }
        boardFetchAges() == ['42 s ago'] * 2

        and: 'each second of the clock moves every board age with it'
        (43..45).each { int second ->
            clockAt(Duration.ofSeconds(second))
            TICKS.eventually {
                assert boardFetchAges() == ["${second} s ago".toString()] * 2
            }
        }
    }

    /** Every {@code <time>} outside the strip: its text, its title and its {@code datetime}. */
    private List<Map> timestamps() {
        browser.script('''
            return Array.prototype.filter.call(document.querySelectorAll("time[data-epoch]"), function (el) {
              return !document.getElementById("freshness").contains(el);
            }).map(function (el) {
              return {text: el.textContent, title: el.title, datetime: el.getAttribute("datetime")};
            });''').collect { it as Map }
    }

    /** The relative ages of the timestamps carrying the page's own instant: the board fetches. */
    private List<String> boardFetchAges() {
        timestamps().findAll {
            it.datetime == DashboardPages.GENERATED_AT.toString()
        }*.text
    }
}
