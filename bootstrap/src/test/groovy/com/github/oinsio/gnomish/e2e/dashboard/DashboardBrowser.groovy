package com.github.oinsio.gnomish.e2e.dashboard

import java.nio.file.Path
import java.time.Instant
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.chromium.HasCdp
import org.openqa.selenium.remote.Augmenter
import org.openqa.selenium.remote.RemoteWebDriver
import org.testcontainers.selenium.BrowserWebDriverContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile

/**
 * One headless Chromium in a Testcontainers {@link BrowserWebDriverContainer}, driven over Selenium's
 * remote protocol, for the dashboard browser specs (design D15, FR14 of tighten-spec-contract).
 *
 * <p><b>Image.</b> {@code selenium/standalone-chromium}, pinned to the Selenium release the build's
 * client resolves (4.43.0, Spring Boot's selenium BOM), so client and server speak one protocol
 * version. Chromium rather than {@code standalone-chrome}: Google ships no arm64 Chrome for Linux,
 * and the Chromium image is multi-architecture, so the layer runs on an arm64 workstation and an
 * amd64 runner alike. The browser runs with {@code --headless=new}.
 *
 * <p><b>How the page reaches the browser.</b> The browser cannot read the host path, so {@link
 * #open} copies the file the production renderer wrote into the container and loads it over {@code
 * file://} — the medium the operator uses (the page is self-contained and makes no request).
 *
 * <p><b>The page clock.</b> The page's script reads the browser clock through {@code Date.now()}.
 * Before any document is created, a CDP {@code Page.addScriptToEvaluateOnNewDocument} script
 * replaces {@code Date.now} with a page clock the spec sets: its start comes from the URL's {@code
 * clock} query parameter (so the script's first tick, during the load, already reads it) and {@link
 * #setPageClock} moves it afterwards. A page loaded without the parameter keeps the real clock.
 *
 * <p>No child process is spawned on the host: the container's environment is what Testcontainers
 * composes (the time zone only), never the operator's (testing.md, "Test processes never inherit the
 * operator's environment").
 *
 * <p>Implements FR14 of tighten-spec-contract (design D15).
 */
final class DashboardBrowser implements AutoCloseable {

    static final DockerImageName IMAGE = DockerImageName.parse('selenium/standalone-chromium:4.43.0')
    .asCompatibleSubstituteFor('selenium/standalone-chrome')

    private static final String PAGE_DIR = '/tmp/dashboard/'
    private static final String PAGE_CLOCK = DashboardBrowser.getResourceAsStream('/dashboard-browser/page-clock.js').text
    private static final String LEGIBILITY = DashboardBrowser.getResourceAsStream('/dashboard-browser/legibility.js').text

    private final BrowserWebDriverContainer container = new BrowserWebDriverContainer(IMAGE)
    .withRecordingMode(BrowserWebDriverContainer.VncRecordingMode.SKIP, null)
    private RemoteWebDriver driver
    private HasCdp cdp

    void start() {
        container.start()
        def options = new ChromeOptions()
        options.addArguments('--headless=new', '--window-size=1280,900')
        driver = new RemoteWebDriver(container.seleniumAddress, options)
        cdp = (HasCdp) new Augmenter().augment(driver)
        cdp.executeCdpCommand('Page.addScriptToEvaluateOnNewDocument', [source: PAGE_CLOCK])
    }

    /**
     * Copies {@code page} into the browser container under {@code name} and loads it, its page
     * clock starting at {@code clock}; returns once the document has loaded.
     */
    void open(Path page, String name, Instant clock) {
        container.copyFileToContainer(MountableFile.forHostPath(page.toString(), 0644), PAGE_DIR + name)
        driver.get("file://${PAGE_DIR}${name}?clock=${clock.toEpochMilli()}")
    }

    /** Moves the page clock the page's script reads; the script sees it on its next tick. */
    void setPageClock(Instant now) {
        script("window.__gfPageClock.set(${now.toEpochMilli()})")
    }

    /**
     * Cancels the document's pending navigations — the scheduled meta-refresh among them — so the
     * page stays the one document while a spec watches its script: the "even the meta-refresh fails
     * to fire" of the dead-renderer scenario.
     */
    void holdMetaRefresh() {
        script('window.stop()')
    }

    /**
     * Evaluates {@code expression} over the page's contrast measurements ({@code gfLegibility}, the
     * {@code legibility.js} resource: {@code texts()}, {@code graphics()}, {@code contrast(el)}).
     */
    def legibility(String expression, Object... args) {
        script(LEGIBILITY + '\nreturn ' + expression + ';', args)
    }

    /** Emulates the operating system's colour scheme, {@code light} or {@code dark}. */
    void emulateColorScheme(String scheme) {
        cdp.executeCdpCommand('Emulation.setEmulatedMedia',
                [features: [
                        [name: 'prefers-color-scheme', value: scheme]
                    ]])
    }

    /** Sets the layout viewport to {@code width} × {@code height} CSS pixels, as a phone has it. */
    void emulateViewport(int width, int height) {
        cdp.executeCdpCommand('Emulation.setDeviceMetricsOverride',
                [width: width, height: height, deviceScaleFactor: 1, mobile: true])
    }

    /** Runs {@code js} in the page and returns its value. */
    def script(String js, Object... args) {
        ((JavascriptExecutor) driver).executeScript(js, args)
    }

    @Override
    void close() {
        driver?.quit()
        container.stop()
    }
}
