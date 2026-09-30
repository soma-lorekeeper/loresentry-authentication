package com.loresentry.authentication.domain;

/** A consent credential has its own storage namespace and grants no session privileges. */
public record ConsentId(String value) {
    public ConsentId {
        new SessionId(value);
    }

    public String hash() {
        return new SessionId(value).hash();
    }

    @Override
    public String toString() {
        return "ConsentId[redacted]";
    }
}
