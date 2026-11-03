package com.github.oinsio.gnomish.executorcontract;

/**
 * The four documents of the executor wire contract, each judged by its own JSON Schema file under
 * {@code schemas/} on this module's classpath ({@link WireSchemas}). A selector the factory names a
 * document with, never a wire token: no document carries it.
 *
 * <p>Implements FR1, FR24 of define-executor-contract.
 */
public enum WireDocument {
    /** The executor's answer to describe. */
    DESCRIBE("executor-describe.schema.json"),
    /** The request the factory sends on an executor's standard input. */
    REQUEST("executor-request.schema.json"),
    /** The result an executor writes to its result file. */
    RESULT("executor-result.schema.json"),
    /** The structured answer an executor writes to its answer file. */
    ANSWER("executor-answer.schema.json");

    private final String schemaFile;

    WireDocument(String schemaFile) {
        this.schemaFile = schemaFile;
    }

    /**
     * The name of the schema file that judges this document, under {@code schemas/}.
     *
     * @return the file name; never blank
     */
    public String schemaFile() {
        return schemaFile;
    }
}
