package com.kaas.runner.command;

/**
 * The runner's refusal to act on a command.
 *
 * <p>Checked rather than unchecked, so a caller cannot ignore it by accident. A command that fails validation
 * must stop the execution, and an exception the compiler does not mention is one somebody eventually forgets
 * to handle.
 */
public class CommandRejected extends Exception {

    public CommandRejected(String message) {
        super(message);
    }
}
