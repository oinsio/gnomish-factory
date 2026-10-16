package com.github.oinsio.gnomish.buildlogic

import groovy.transform.Immutable

/**
 * One spec file the gate judges: where it is (relative to the project directory, for findings),
 * the capability it belongs to, and its structure as {@link SpecContractReader} read it. For a
 * main spec {@code specId} is the spec directory relative to the spec tree
 * ({@code verification/verification-hardening}); for an unarchived delta it is the capability
 * path under the change's {@code specs/} directory.
 *
 * <p>Implements FR1, FR2 and FR5 of tighten-spec-contract.
 */
@Immutable(knownImmutableClasses = [SpecDocument])
class SpecFile {

    String path
    String specId
    SpecDocument document

    /** Reads every {@code spec.md} under {@code root}, its spec id taken relative to {@code specIdRoot}. */
    static List<SpecFile> readAll(File baseDir, Collection<File> files, Closure<File> specIdRoot) {
        files.findAll { it.isFile() && it.name == 'spec.md' }.sort { it.path }.collect { File file ->
            String specId = relative(specIdRoot(file), file.parentFile)
            new SpecFile(path: relative(baseDir, file), specId: specId,
                    document: SpecContractReader.readSpec(file))
        }
    }

    /** {@code file} relative to {@code base}, with {@code /} separators. */
    static String relative(File base, File file) {
        base.toPath().relativize(file.toPath()).toString().replace(File.separator, '/')
    }
}
