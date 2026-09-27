package com.loresentry.authentication.application.port.out;

import com.loresentry.authentication.domain.SessionId;

public interface SessionIdGenerator {
    SessionId generate();
}
