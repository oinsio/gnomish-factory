package com.github.oinsio.gnomish.e2e.dashboard

import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import java.time.Duration
import spock.lang.IgnoreIf
import spock.lang.Timeout

/**
 * The page at a phone's width, laid out by a browser (requirement "Page fits a narrow viewport" of
 * {@code observability/dashboard-page}; finding F93, design D15): the page the production renderer
 * wrote, with every block populated and task titles that cannot break, in a 375-pixel viewport.
 *
 * <p>Implements FR14 of tighten-spec-contract (design D15); UX4 of redesign-dashboard.
 */
@Timeout(300)
@IgnoreIf(value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unreachable; Docker is a dev/CI prerequisite of the Docker-dependent E2E layer (.claude/rules/testing.md)')
class DashboardPhoneWidthBrowserSpec extends DashboardBrowserSpecification {

    private static final int PHONE_WIDTH = 375

    // Scenario: observability/dashboard-page / Phone-width render does not overflow
    def "UX4: in a 375-pixel viewport no element extends past the viewport and nothing scrolls sideways"() {
        given:
        browser.emulateViewport(PHONE_WIDTH, 800)
        openAt(Duration.ofSeconds(1))

        expect: 'the viewport really is 375 pixels wide'
        browser.script('return document.documentElement.clientWidth') == PHONE_WIDTH

        and: 'no horizontal scrollbar: the document is no wider than the viewport'
        (browser.script('return document.documentElement.scrollWidth') as int) <= PHONE_WIDTH

        and: 'no rendered element extends beyond either edge of the viewport'
        overflowing() == []
    }

    /** Every rendered element whose box crosses the viewport's left or right edge, by its tag and class. */
    private List<String> overflowing() {
        browser.script('''
            var width = document.documentElement.clientWidth;
            return Array.prototype.filter.call(document.body.querySelectorAll("*"), function (el) {
              var box = el.getBoundingClientRect();
              if (box.width === 0 && box.height === 0) return false;
              return box.left < -0.5 || box.right > width + 0.5;
            }).map(function (el) {
              return el.tagName.toLowerCase() + "." + (el.getAttribute("class") || "") + " right=" +
                  el.getBoundingClientRect().right;
            });''') as List<String>
    }
}
