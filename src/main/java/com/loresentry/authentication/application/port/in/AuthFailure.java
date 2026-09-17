package com.loresentry.authentication.application.port.in;

import java.util.Objects;

/** Application failures carry meaning, never HTTP status or framework exceptions. */
public class AuthFailure extends RuntimeException {
    public enum Reason {
        INVALID_REQUEST, INVALID_DISPLAY_NAME, OAUTH_REQUEST_INVALID, OAUTH_LOGIN_DENIED,
        OAUTH_IDENTITY_INVALID, REFRESH_REJECTED, INVALID_REFRESH_TOKEN,
        USER_CONTEXT_REQUIRED, USER_NOT_FOUND, LOGIN_UNAVAILABLE, REFRESH_UNAVAILABLE,
        REFRESH_OUTCOME_UNKNOWN, REFRESH_SAVE_FAILED, REVOCATION_UNCONFIRMED,
        ACCOUNT_UNAVAILABLE, INTERNAL_ERROR
    }

    public enum Consumption { NOT_CONSUMED, CONSUMED, UNKNOWN }

    private final Reason reason;
    private final Consumption consumption;

    public AuthFailure(Reason reason) { this(reason, null); }

    public AuthFailure(Reason reason, Consumption consumption) {
        this(reason, consumption, null);
    }

    public AuthFailure(Reason reason, Consumption consumption, Throwable cause) {
        super(Objects.requireNonNull(reason).name(), cause);
        this.reason = reason;
        this.consumption = consumption;
    }

    public Reason reason() { return reason; }
    /** Null means this is not a callback failure; UNKNOWN is an unconfirmed consumption. */
    public Consumption consumption() { return consumption; }
}
