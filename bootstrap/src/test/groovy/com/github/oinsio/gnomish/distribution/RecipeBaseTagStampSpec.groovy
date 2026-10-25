package com.github.oinsio.gnomish.distribution

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import com.github.oinsio.gnomish.testsupport.TestTaskProperty
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR9 of add-sandbox-base-image: the reference recipe the operator archive ships builds {@code FROM}
 * the base of the factory release it ships with. {@code packaging.gradle} stamps the release's base
 * tag (design D11) into the {@code ARG GNOMISH_BASE_TAG=} line of the shipped Dockerfile and of the
 * shipped README; a local build ({@code 0.0.0-dev}) ships both unchanged. Read from the real
 * {@code distZip} output, as {@link DistributionLayoutSpec} reads it; a build run with
 * {@code -PreleaseVersion=X.Y.Z} or {@code X.Y.Z-<pre>} exercises the stamping branch.
 *
 * <p>{@link #expectedTag} derives the tag on its own, on purpose: it is the spec's oracle, and a
 * spec reading the build's {@code baseTag} provider would compare the owner with itself (design
 * D11, single-owner row 4, exemption).
 */
class RecipeBaseTagStampSpec extends Specification {

    private static final String TAG_ARG = 'ARG GNOMISH_BASE_TAG='

    @Shared
    String version = TestTaskProperty.required('e2e.productVersion')

    @Shared
    Path zip = Path.of(TestTaskProperty.required('e2e.distZipPath'))

    // FR9: only the base tag's default differs between the repository's recipe and the shipped one
    def "FR9: the shipped #file's GNOMISH_BASE_TAG default is the release's base tag, or the repository's default"() {
        given:
        def repository = Files.readAllLines(RepoSourceTree.repoRoot().resolve("docs/examples/sandbox-image/${file}"))
        def shipped = shippedRecipe(file).readLines()
        def repositoryTag = repository.find { it.startsWith(TAG_ARG) }

        expect:
        repositoryTag != null
        shipped.size() == repository.size()
        shipped.find {
            it.startsWith(TAG_ARG)
        } == TAG_ARG + expectedTag(repositoryTag.substring(TAG_ARG.length()))
        shipped.findAll {
            !it.startsWith(TAG_ARG)
        } == repository.findAll {
            !it.startsWith(TAG_ARG)
        }

        where:
        file << ['Dockerfile', 'README.md']
    }

    /** The oracle: X.Y of a version without a suffix, the whole version with one, none for a dev build. */
    private String expectedTag(String repositoryDefault) {
        if (version == '0.0.0-dev') {
            return repositoryDefault
        }
        def release = version =~ /^(\d+)\.(\d+)\.\d+$/
        version.contains('-') ? version : (release.find() ? "${release.group(1)}.${release.group(2)}" : 'unexpected')
    }

    private String shippedRecipe(String file) {
        new ZipFile(zip.toFile()).withCloseable { archive ->
            def entry = archive.getEntry("gnomish-${version}/share/sandbox-image/${file}")
            assert entry: "the archive ships no share/sandbox-image/${file}"
            new String(archive.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)
        }
    }
}
