package com.loresentry.authentication.domain;

import java.time.Instant;

public record Acquisition(
        String utmSource,
        String utmMedium,
        String utmCampaign,
        String utmTerm,
        String utmContent,
        String clickId,
        String clickIdType,
        String referrerHost,
        String landingPath,
        Instant landedAt) {
    public Acquisition {
        utmSource = present(utmSource);
        utmMedium = present(utmMedium);
        utmCampaign = present(utmCampaign);
        utmTerm = present(utmTerm);
        utmContent = present(utmContent);
    }

    private static String present(String value) {
        if (value == null) return null;
        var stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
