package com.loresentry.authentication.domain;

import java.util.UUID;

public record OAuthIdentity(String provider, String providerId, UUID userId, String email) {}
