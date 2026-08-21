package com.github.oinsio.gnomish.sandbox.environment

/**
 * Shared {@link Process} test-double boilerplate: an empty stderr and a no-op
 * {@code destroy()} — the two methods every canned-output process fake in this
 * package ({@code ScriptedProcess}, {@code FakeExecProcess}) must stub
 * identically because {@link Process} forces an override even when the fake
 * never uses them.
 */
abstract class FakeProcess extends Process {

    @Override
    InputStream getErrorStream() {
        new ByteArrayInputStream(new byte[0])
    }

    @Override
    void destroy() {
    }
}
