package com.loresentry.authentication.application.port.out;

import java.util.Objects;

/** Adapters must not claim NOT_EXECUTED merely because a command timed out. */
public class PortFailure extends RuntimeException {
    public enum Kind { UNAVAILABLE, INVALID_DATA, IDENTITY_ALREADY_REGISTERED, INVALID_IDENTITY, INVALID_TOKEN }
    public enum Execution { NOT_EXECUTED, UNKNOWN }

    private final Kind kind;
    private final Execution execution;
    private final boolean retryable;

    public PortFailure(Kind kind, Execution execution, boolean retryable) {
        // Raw SQL, provider responses and credentials never become exception messages.
        super(Objects.requireNonNull(kind).name());
        this.kind = kind;
        this.execution = Objects.requireNonNull(execution);
        this.retryable = retryable;
    }

    public Kind kind() { return kind; }
    public Execution execution() { return execution; }
    public boolean retryable() { return retryable; }
}
