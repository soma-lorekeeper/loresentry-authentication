package com.loresentry.authentication.domain;

import java.time.Instant;
import java.util.UUID;

public record TermsVersion(
        UUID id,
        String version,
        String title,
        String content,
        Instant publishedAt,
        Instant effectiveAt) {}
