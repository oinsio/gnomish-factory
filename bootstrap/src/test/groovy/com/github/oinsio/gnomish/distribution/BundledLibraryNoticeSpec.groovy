package com.github.oinsio.gnomish.distribution

import com.github.oinsio.gnomish.testsupport.TestTaskProperty
import java.security.MessageDigest
import java.util.jar.JarFile
import spock.lang.Shared
import spock.lang.Specification

/**
 * The third-party libraries the boot jar bundles keep their own license terms (FR3 of
 * add-project-license): Spring Boot nests each library jar as it was resolved, so the
 * {@code META-INF/LICENSE} and {@code META-INF/NOTICE} a library ships stay inside it.
 *
 * <p>Compared byte for byte with the same library on this spec's own classpath: {@code :bootstrap}'s
 * {@code runtimeClasspath} and {@code testRuntimeClasspath} resolve the same locked versions
 * ({@code gradle.lockfile}), so every bundled third-party jar has a twin of the same file name.
 * Reads the built boot jar at {@code e2e.jarPath} ({@code verification.gradle}).
 */
class BundledLibraryNoticeSpec extends Specification {

    private static final String LIB = 'BOOT-INF/lib/'

    /** Resolved external artifacts live in Gradle's module cache; the factory's own jars do not. */
    private static final String MODULE_CACHE = '/caches/modules-2/'

    /** This spec's own classpath jars by file name: the resolved artifacts and the factory's module jars. */
    @Shared
    Map<String, File> classpathJars = System.getProperty('java.class.path')
    .split(File.pathSeparator)
    .collect { new File(it) }
    .findAll { it.name.endsWith('.jar') }
    .collectEntries { [(it.name): it] }

    // Scenario: release/project-licensing / Bundled libraries keep their own notices
    def "FR3: every bundled third-party jar is its resolved artifact unmodified, its own terms intact"() {
        given:
        Map<String, byte[]> nested = nestedJars()
        Map<String, byte[]> bundled = nested.findAll { name, bytes ->
            classpathJars[name]?.path?.contains(MODULE_CACHE)
        }

        expect: 'every nested jar is a resolved artifact or one of the factory\'s own module jars'
        nested.keySet().findAll { !classpathJars.containsKey(it) } == [] as Set

        and: 'the scan reached the third-party libraries, and some of them ship their own terms'
        bundled.size() >= KNOWN_THIRD_PARTY_JARS
        bundled.count { name, bytes ->
            entryNames(bytes).any {
                it in TERMS
            }
        } >= 1

        and: 'each is byte-identical to the artifact Gradle resolved, so its terms are the ones it shipped'
        bundled.findAll { name, bytes ->
            sha256(bytes) != sha256(classpathJars[name].bytes)
        }.keySet() == [] as Set
    }

    /** A boot jar bundling fewer than this many libraries would make the comparison vacuous. */
    private static final int KNOWN_THIRD_PARTY_JARS = 10

    private static final List<String> TERMS = [
        'META-INF/LICENSE',
        'META-INF/LICENSE.txt',
        'META-INF/NOTICE',
        'META-INF/NOTICE.txt'
    ]

    /** The library jars nested in the boot jar, by file name. */
    private static Map<String, byte[]> nestedJars() {
        new JarFile(TestTaskProperty.required('e2e.jarPath')).withCloseable { jar ->
            jar.entries().toList()
            .findAll {
                it.name.startsWith(LIB) && it.name.endsWith('.jar')
            }
            .collectEntries {
                [(it.name.substring(LIB.length())): jar.getInputStream(it).bytes]
            }
        }
    }

    private static List<String> entryNames(byte[] jarBytes) {
        def names = []
        new java.util.zip.ZipInputStream(new ByteArrayInputStream(jarBytes)).withCloseable { zip ->
            for (def entry = zip.nextEntry; entry != null; entry = zip.nextEntry) {
                names << entry.name
            }
        }
        names
    }

    private static String sha256(byte[] bytes) {
        MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
    }
}
