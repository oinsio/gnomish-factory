package com.github.oinsio.gnomish.adapter.git;

/**
 * The two {@code .gnomish-task/} file paths every branch reader and writer in this module
 * addresses by the same repository-relative string, so the literal exists once instead of being
 * retyped per class.
 */
final class GnomishTaskPaths {

    static final String TASK_JSON_PATH = ".gnomish-task/task.json";
    static final String STATE_JSON_PATH = ".gnomish-task/state.json";

    private GnomishTaskPaths() {}
}
