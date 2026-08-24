package com.github.oinsio.gnomish.dashboard;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The page's stylesheet and client script, held as text and inlined into
 * every render (FR10, design D1). Both live as normal files under {@code
 * src/main/resources/dashboard/} — reviewable, format-gated, IDE-supported —
 * rather than as Java string constants, and are read once by {@link
 * DashboardHtmlRenderer}'s constructor: the page regenerates every few
 * seconds, so re-reading the classpath per render would be pure waste.
 *
 * <p>Three things fail here rather than in a browser (NFR-R1, design D2): a
 * resource missing from the classpath, a resource that is present but holds
 * nothing — a dashboard that silently renders unstyled is worse than one
 * that refuses to start, and an empty file reaches exactly that outcome
 * without the classpath being wrong — and content holding an early
 * terminator for its own inline block, which would end that block mid-file
 * and leave a half-parsed page that is very confusing to debug. Each
 * resource is guarded by the terminator of the block it is inlined into and
 * by no other: {@code <style>} raw text ends only at {@code </style},
 * {@code <script>} only at {@code </script}, so a stylesheet may name
 * {@code </script>} in a {@code content} value harmlessly and a script may
 * name {@code </style>}. The guard is a pattern, not an escape pass, so the
 * resources stay exactly what a reviewer reads.
 *
 * <p>Every refusal names the file and what to do about it: these fire at
 * daemon start, where the operator reading the message is not the person who
 * wrote the resource.
 *
 * <p>Implements FR10, NFR-R1 of redesign-dashboard (design D1, D2).
 *
 * @param css the stylesheet text, inlined into the page's {@code <style>} block; never null or blank
 * @param js the client script text, inlined into the page's {@code <script>} block; never null or blank
 */
record DashboardResources(String css, String js) {

    /** Classpath location of the stylesheet (design D1). */
    private static final String CSS_PATH = "/dashboard/dashboard.css";

    /** Classpath location of the client script (design D1). */
    private static final String JS_PATH = "/dashboard/dashboard.js";

    /** Anything a browser would read as the end of the inline {@code <style>} block (design D2). */
    private static final Pattern STYLE_TERMINATOR = Pattern.compile("(?i)</\\s*style");

    /** Anything a browser would read as the end of the inline {@code <script>} block (design D2). */
    private static final Pattern SCRIPT_TERMINATOR = Pattern.compile("(?i)</\\s*script");

    /** Where a resource is authored, named in every refusal so the fix needs no source hunt. */
    private static final String SOURCE_DIR = "application/src/main/resources";

    DashboardResources {
        requireInlineable(css, CSS_PATH, STYLE_TERMINATOR);
        requireInlineable(js, JS_PATH, SCRIPT_TERMINATOR);
    }

    /**
     * Opens one classpath resource. A seam, so the unreadable-stream path of
     * {@link #read(String, ResourceSource)} is reachable from a unit spec —
     * a real classpath resource cannot be made to fail mid-read.
     */
    @FunctionalInterface
    interface ResourceSource {

        /**
         * Opens the resource.
         *
         * @return the open stream, or {@code null} when the classpath carries no such resource
         */
        @Nullable
        InputStream open();
    }

    /**
     * Reads both resources from the classpath.
     *
     * @return the loaded resources; never null
     * @throws IllegalStateException when either resource is absent, blank, or holds an early
     *     terminator for the block it is inlined into
     */
    static DashboardResources load() {
        return new DashboardResources(read(CSS_PATH), read(JS_PATH));
    }

    /**
     * Reads one classpath resource as UTF-8 text.
     *
     * @param path the absolute classpath location; never null
     * @return the resource's text; never null
     * @throws IllegalStateException when the classpath carries no such resource
     */
    static String read(String path) {
        return read(path, () -> DashboardResources.class.getResourceAsStream(path));
    }

    /**
     * Reads one resource as UTF-8 text from {@code source}.
     *
     * @param path the absolute classpath location, for the refusal messages; never null
     * @param source opens the resource; never null
     * @return the resource's text; never null
     * @throws IllegalStateException when the source yields no resource
     * @throws UncheckedIOException when the resource is present but cannot be read through
     */
    static String read(String path, ResourceSource source) {
        try (InputStream stream = source.open()) {
            if (stream == null) {
                throw new IllegalStateException("dashboard resource missing from the classpath: " + path
                        + " — restore it under " + SOURCE_DIR + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "dashboard resource unreadable: " + path + " — check " + SOURCE_DIR + path
                            + " and the packaged jar it is read from",
                    unreadable);
        }
    }

    private static void requireInlineable(String content, String path, Pattern terminator) {
        if (content.isBlank()) {
            throw new IllegalStateException("dashboard resource is empty: " + path
                    + " — the page would render unstyled or scriptless; restore its content in "
                    + SOURCE_DIR + path);
        }
        if (terminator.matcher(content).find()) {
            throw new IllegalStateException("dashboard resource would terminate its inline block early: " + path
                    + " — rewrite the offending sequence in " + SOURCE_DIR + path
                    + " (split the literal, or escape the slash)");
        }
    }
}
