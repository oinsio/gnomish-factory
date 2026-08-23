package com.github.oinsio.gnomish.testsupport

/**
 * The one page-slicing helper every dashboard spec shares: the rendered page
 * minus its inlined stylesheet, so an absence check ({@code !contains(...)})
 * cannot trip over a CSS rule that merely names the same class.
 */
class DashboardPageMarkup {

    /** The page from the end of its inlined stylesheet onward. */
    static String markup(String html) {
        html.substring(html.indexOf('</style>'))
    }
}
