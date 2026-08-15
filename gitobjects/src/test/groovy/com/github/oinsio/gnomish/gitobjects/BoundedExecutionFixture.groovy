package com.github.oinsio.gnomish.gitobjects

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs a call on a daemon executor with a hard deadline: a mutant that turns a bounded read/growth
 * loop into a busy-spin with no blocking I/O to interrupt would otherwise hang the calling thread
 * (and the test) forever — bounding the wait turns that into a fast, clean failure. The daemon
 * thread itself is abandoned on timeout, never blocking JVM/minion shutdown.
 */
trait BoundedExecutionFixture {

    static <T> T withBoundedWait(Closure<T> work) {
        def executor = Executors.newSingleThreadExecutor { runnable ->
            def thread = new Thread(runnable)
            thread.daemon = true
            thread
        }
        try {
            return executor.submit(work as Callable<T>).get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}
