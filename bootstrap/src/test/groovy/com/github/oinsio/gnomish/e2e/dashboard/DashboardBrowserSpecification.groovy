package com.github.oinsio.gnomish.e2e.dashboard

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions

/**
 * The shared shape of the dashboard browser specs (design D15, FR14 of tighten-spec-contract): one
 * browser container per spec class, and one {@code --watch} page the production renderer wrote,
 * loaded fresh by every feature. Each concrete spec carries the layer's Docker skip ({@code
 * @IgnoreIf} on {@code GiteaAvailability.dockerAvailable()}, the probe the Docker-dependent E2E layer
 * shares), so a machine without a daemon reports the specs skipped, never failed.
 *
 * <p>Implements FR14 of tighten-spec-contract (design D15).
 */
abstract class DashboardBrowserSpecification extends Specification {

    /** How long a feature waits for the page's script to show a state: a few of its one-second ticks. */
    static final PollingConditions TICKS = new PollingConditions(timeout: 5, delay: 0.1)

    @Shared
    @TempDir
    Path home

    @Shared
    Path page

    @Shared
    DashboardBrowser browser = new DashboardBrowser()

    def setupSpec() {
        page = DashboardPages.watchPage(home)
        browser.start()
    }

    def cleanupSpec() {
        browser.close()
    }

    /** Loads the page, its page clock {@code age} after the page's {@code generatedAt}. */
    void openAt(Duration age) {
        browser.open(page, 'dashboard.html', at(age))
    }

    /** Moves the page clock to {@code age} after the page's {@code generatedAt}. */
    void clockAt(Duration age) {
        browser.setPageClock(at(age))
    }

    /** The freshness strip as the reader sees it: its state, its sentence and its background. */
    Map strip() {
        browser.script('''
            var strip = document.getElementById("freshness");
            return {
              state: strip.dataset.state,
              text: document.getElementById("freshness-text").textContent,
              background: getComputedStyle(strip).backgroundColor
            };''') as Map
    }

    /** The colour {@code token} resolves to under the current colour scheme, as computed CSS. */
    String token(String token) {
        browser.script('''
            var probe = document.createElement("div");
            probe.style.backgroundColor = "var(" + arguments[0] + ")";
            document.body.appendChild(probe);
            var colour = getComputedStyle(probe).backgroundColor;
            probe.remove();
            return colour;''', token)
    }

    private static Instant at(Duration age) {
        DashboardPages.GENERATED_AT.plus(age)
    }
}
