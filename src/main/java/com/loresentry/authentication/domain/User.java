package com.loresentry.authentication.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 서비스 사용자다.
 *
 * @param locale 사용자가 선택한 언어. 기록한 적이 없으면 null
 */
public record User(
        UUID id,
        String displayName,
        Instant createdAt,
        Instant updatedAt,
        Instant onboardingCompletedAt,
        SupportedLocale locale) {}
