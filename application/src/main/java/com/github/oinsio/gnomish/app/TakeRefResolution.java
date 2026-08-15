package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.util.Map;

/**
 * Builds the {@link TaskRef} for one explicit-mode {@code <ref>} string (FR9 of add-tracker-port):
 * a ref matching the short-ref shape (`42`, `#42`) is expanded via the registered adapter factory
 * for {@code trackerConfig.type()}; anything else (an already-canonical ref) is wrapped as-is.
 * Extracted from {@link TakeDispatcher} so that class stays within the file-size cap — used by
 * both {@link TakeDispatcher#runOneRef} and, through it, batch mode (task 6.2 of add-factory-serve).
 *
 * <p>Implements FR9 of add-tracker-port.
 */
final class TakeRefResolution {

    private TakeRefResolution() {}

    /**
     * @throws UsageException if {@code ref} looks like a short ref but no adapter factory is
     *     registered for {@code trackerConfig.type()}
     */
    static TaskRef resolve(String ref, TrackerConfig trackerConfig, Map<String, TrackerAdapterFactory> registry) {
        if (!ShortRef.isShortRef(ref)) {
            return new TaskRef(ref);
        }
        TrackerAdapterFactory factory = registry.get(trackerConfig.type());
        if (factory == null) {
            throw new UsageException("cannot expand short ref '" + ref + "': unknown tracker type '"
                    + trackerConfig.type() + "' — supported: " + TakeCommandSupport.supportedTypes(registry));
        }
        return factory.expandRef(trackerConfig, ref);
    }
}
