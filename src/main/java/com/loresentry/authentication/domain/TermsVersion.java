package com.loresentry.authentication.domain;

import java.time.Instant;
import java.util.UUID;

public record TermsVersion(
        UUID id,
        String version,
        String title,
        String content,
        Instant publishedAt,
        Instant effectiveAt) {
    /** terms_versions에 저장한 원문의 언어. 번역은 같은 버전에 연결되며 새 버전을 만들지 않는다. */
    public static final SupportedLocale ORIGINAL_LOCALE = SupportedLocale.KO;

    public TermsText original() {
        return new TermsText(ORIGINAL_LOCALE, title, content);
    }
}
