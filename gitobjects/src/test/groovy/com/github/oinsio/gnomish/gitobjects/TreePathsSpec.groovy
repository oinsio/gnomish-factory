package com.github.oinsio.gnomish.gitobjects

import spock.lang.Specification

/**
 * FR25/FR17: {@link TreePaths#validate} — a valid repository-relative path is returned as-is (the
 * exact value the caller passed in, not merely a non-throwing truthy result).
 */
class TreePathsSpec extends Specification {

    def "FR25/FR17: validate returns the exact input path unchanged, not a placeholder (#path)"() {
        expect:
        TreePaths.validate(path) == path

        where:
        path << [
            'file.txt',
            'a/b/c.txt',
            '.gnomish-task/task.json',
            'src/App.java'
        ]
    }
}
