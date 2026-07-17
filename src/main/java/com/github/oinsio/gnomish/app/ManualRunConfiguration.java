package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO;
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence;
import com.github.oinsio.gnomish.adapter.engine.SystemClock;
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper;
import java.time.Clock;
import java.util.Random;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles every {@code gnomish run} collaborator that needs no per-invocation data — the
 * context-independent half of {@link com.github.oinsio.gnomish.domain.engine.EnginePorts}'s bean
 * graph (design D10). The remaining collaborators (the interactive adapters, the status snapshot
 * pipeline, {@code EnginePorts} itself) depend on the {@link
 * com.github.oinsio.gnomish.domain.engine.TaskContext} synthesized from {@code --task}/{@code
 * --task-file} at runtime and cannot be known at Spring context-refresh time; {@link
 * ManualRunRunner} builds those imperatively once that context exists, using the beans here as
 * building blocks.
 *
 * <p>{@link Random} and {@link Clock} beans are the two collaborators {@link
 * AdHocTaskSynthesizer} needs — kept unseeded/system-real here since a manual run always wants a
 * genuine timestamp and a genuine random suffix; tests construct their own seeded instances
 * directly rather than through this configuration (see {@code AdHocTaskSynthesizerSpec}).
 *
 * <p>Implements D10 of add-manual-run.
 */
@Configuration
public class ManualRunConfiguration {

    @Bean
    public FilesExistCheckRunner filesExistCheckRunner() {
        return new FilesExistCheckRunner();
    }

    @Bean
    public ShellCommandCheckRunner shellCommandCheckRunner() {
        return new ShellCommandCheckRunner();
    }

    @Bean
    public InMemoryAttemptPersistence attemptPersistence() {
        return new InMemoryAttemptPersistence();
    }

    @Bean
    public SystemClock systemClock() {
        return new SystemClock();
    }

    @Bean
    public ThreadSleeper threadSleeper() {
        return new ThreadSleeper();
    }

    /** The real {@link com.github.oinsio.gnomish.adapter.console.ConsoleIO}, wrapping the process's own stdin/stdout. */
    @Bean
    public SystemConsoleIO systemConsoleIO() {
        return new SystemConsoleIO(System.in, System.out);
    }

    @Bean
    public Clock javaTimeClock() {
        return Clock.systemUTC();
    }

    @Bean
    public Random taskIdRandom() {
        return new Random();
    }

    @Bean
    public AdHocTaskSynthesizer adHocTaskSynthesizer(Clock javaTimeClock, Random taskIdRandom) {
        return new AdHocTaskSynthesizer(javaTimeClock, taskIdRandom);
    }
}
