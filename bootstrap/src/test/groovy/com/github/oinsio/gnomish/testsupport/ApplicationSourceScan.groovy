package com.github.oinsio.gnomish.testsupport

import java.util.regex.Pattern

/**
 * The scan over {@code application/src/main} the owner gates of that layer share
 * ({@code DaemonLoopOwnerBoundarySpec}, {@code DashboardOwnerBoundarySpec}): which files spell a
 * token or match a detector in code, comments stripped through {@link RepoSourceTree#code}, and
 * which listed paths the scan did not reach, so a moved owner fails instead of widening a gate.
 */
class ApplicationSourceScan {

    /** The source root of the application layer's main package. */
    static final String APP = 'application/src/main/java/com/github/oinsio/gnomish/'

    static List<File> applicationSources() {
        RepoSourceTree.productionSources { String path ->
            path.startsWith('application/src/main/')
        }
    }

    /** The listed paths the scan did not reach: moved, renamed or deleted files. */
    static Set<String> unreached(Collection<String> paths) {
        paths.toSet() - applicationSources().collect {
            RepoSourceTree.relative(it)
        }.toSet()
    }

    /** The application files matching any of the detectors in code, outside comments. */
    static Set<String> detecting(List<Pattern> detectors) {
        applicationSources().findAll { file ->
            matchesAny(RepoSourceTree.code(file), detectors)
        }
        .collect { RepoSourceTree.relative(it) }
        .toSet()
    }

    static boolean matchesAny(String code, List<Pattern> detectors) {
        detectors.any { it.matcher(code).find() }
    }

    /** The application files spelling any of the tokens in code, outside comments. */
    static Set<String> spelling(List<String> tokens) {
        applicationSources().findAll { file ->
            def code = RepoSourceTree.code(file)
            tokens.any { code.contains(it) }
        }
        .collect { RepoSourceTree.relative(it) }
        .toSet()
    }
}
