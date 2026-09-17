package com.loresentry.authentication.domain;

import java.time.Instant;
import java.util.UUID;

public record User(UUID id, String displayName, Instant createdAt, Instant updatedAt) {}
