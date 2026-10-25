package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR6, M2 of add-sandbox-base-image: the detector of {@link ImageContractSourceSpec} over seeded
 * texts. Over a clean tree the gate finds nothing, so a rule that stopped matching would look exactly
 * like a tree that stopped offending; these rows tell the two apart. Each seed writes {@code ALIAS}
 * and {@code PORT} for the values of {@code sandbox-image/image-contract.properties}, so the rows
 * follow the file and no value is spelled here either.
 */
class ImageContractSpellingSpec extends Specification {

    @Shared
    Properties contract = new Properties().tap { properties ->
        Files.newInputStream(RepoSourceTree.repoRoot().resolve('sandbox-image/image-contract.properties')).withCloseable {
            properties.load(it)
        }
    }

    // FR6, M2: every realistic spelling of a value is found, and the path and box-name uses of the
    //     alias's text, which are not the alias, are left alone.
    def "FR6, M2: #shape is #expected"() {
        given:
        def text = seed.replace('ALIAS', contract.getProperty('guard-alias'))
                .replace('PORT', contract.getProperty('guard-port'))

        expect:
        ImageContractSpelling.spelled(text, contract.getProperty('guard-alias'), contract.getProperty('guard-port')) == expected

        where:
        shape | seed || expected
        'a quoted alias' | 'static final String PROXY_ALIAS = "ALIAS";' || ['alias']
        'a Dockerfile ARG default' | 'ARG PROXY_HOST=ALIAS' || ['alias']
        'a proxy URL' | 'HTTP_PROXY=http://ALIAS:PORT' || ['alias', 'port']
        'an alias in prose' | ' * the guard answers on ALIAS inside the box' || ['alias']
        'an alias ending a sentence' | ' * its alias is ALIAS.' || ['alias']
        'a Dockerfile ARG port' | 'ARG PROXY_PORT=PORT' || ['port']
        'a Java port constant' | 'static final int PROXY_PORT = PORT;' || ['port']
        'a port as a word' | 'EXPOSE PORT' || ['port']
        'a LABEL line' | 'LABEL io.gnomish.image-contract=1' || ['label']
        'a quoted --label argument' | '"--label", "io.gnomish.image-contract=2"' || ['label']
        'a map entry' | '"io.gnomish.image-contract": "1"' || ['label']
        'the mount path' | 'static final String CONFIG_MOUNT = "/ALIAS";' || []
        'a file under the mount' | 'open("/ALIAS/allowlist.json")' || []
        'a box name' | ' * named {@code ALIAS-<key>}' || []
        'a longer word' | 'ALIASian' || []
        'the label key from a build arg' | 'LABEL io.gnomish.image-contract="${IMAGE_CONTRACT}"' || []
        'a longer number' | 'int timeout = 1PORT; long id = PORT1;' || []
        'a version number' | 'version 1.PORT or PORT.2' || []
    }
}
