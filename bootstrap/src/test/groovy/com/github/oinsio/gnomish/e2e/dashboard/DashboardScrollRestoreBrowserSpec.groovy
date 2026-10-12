package com.github.oinsio.gnomish.e2e.dashboard

import com.github.oinsio.gnomish.app.DashboardWatch
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import java.time.Duration
import spock.lang.IgnoreIf
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * The watch page keeps the reader's place across its own meta-refresh (requirement "Reload keeps the
 * reading position" of {@code observability/dashboard-page}; finding F93, design D15): the page the
 * production renderer wrote reloads itself every {@link DashboardWatch#RENDER_CADENCE}, and its
 * script saves the scroll position before the unload and restores it after the load. Here the
 * meta-refresh really fires.
 *
 * <p>Implements FR14 of tighten-spec-contract (design D15); UX5 of redesign-dashboard.
 */
@Timeout(300)
@IgnoreIf(value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unreachable; Docker is a dev/CI prerequisite of the Docker-dependent E2E layer (.claude/rules/testing.md)')
class DashboardScrollRestoreBrowserSpec extends DashboardBrowserSpecification {

    /** Longer than one render cadence, the meta-refresh interval, with room for the reload itself. */
    private static final PollingConditions RELOAD = new PollingConditions(
    timeout: DashboardWatch.RENDER_CADENCE.seconds * 2, delay: 0.2)

    private static final int READING_POSITION = 500

    // Scenario: observability/dashboard-page / Refresh does not reset scroll
    def "UX5: the meta-refresh reloads the page at the position the reader had scrolled to"() {
        given: 'a phone-sized viewport, so the page is taller than the screen, and the page open'
        browser.emulateViewport(375, 600)
        openAt(Duration.ofSeconds(1))
        assert (browser.script('return document.documentElement.scrollHeight - window.innerHeight') as int) > READING_POSITION

        and: 'the reader scrolled down; the current document is marked so a reload is visible'
        browser.script("window.scrollTo(0, ${READING_POSITION}); window.__gfBeforeRefresh = true")
        assert (browser.script('return window.scrollY') as int) == READING_POSITION

        when: 'the meta-refresh fires'
        RELOAD.eventually {
            assert browser.script('return window.__gfBeforeRefresh === undefined && document.readyState === "complete"') == true
        }

        then: 'the reloaded page is at the position the reader left'
        TICKS.eventually {
            assert (browser.script('return window.scrollY') as int) == READING_POSITION
        }
    }
}
