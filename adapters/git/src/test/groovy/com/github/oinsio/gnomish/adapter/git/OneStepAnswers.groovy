package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path

/** The real runner, but the one step {@code matches} picks answers {@code answer}. */
final class OneStepAnswers implements GitRunner {

    private final GitRunner real
    private final Closure<Boolean> matches
    private final GitCommandResult answer

    OneStepAnswers(GitRunner real, Closure<Boolean> matches, GitCommandResult answer) {
        this.real = real
        this.matches = matches
        this.answer = answer
    }

    @Override
    GitCommandResult run(Path cwd, String... args) {
        matches.call(args as List) ? answer : real.run(cwd, args)
    }

    @Override
    GitCommandResult run(Path cwd, com.github.oinsio.gnomish.gittransfer.GitTransfer transfer) {
        real.run(cwd, transfer)
    }

    @Override
    GitCommandResult run(Path cwd, RefTransaction transaction) {
        real.run(cwd, transaction)
    }
}
