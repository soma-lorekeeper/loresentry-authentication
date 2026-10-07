package com.loresentry.authentication.application.port.out;

import com.loresentry.authentication.domain.SupportedLocale;
import com.loresentry.authentication.domain.TermsText;
import com.loresentry.authentication.domain.TermsVersion;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TermsVersionStore {
    /** Latest published and effective SERVICE_TERMS at the supplied server time. */
    Optional<TermsVersion> current(Instant now);

    /**
     * 약관 버전에 연결된 번역 제목과 본문을 조회한다.
     *
     * @param termsVersionId 번역 대상 약관 버전
     * @param locale 번역 언어
     * @return 번역 본문. 번역이 없으면 빈 Optional이며 저장소 장애를 뜻하지 않음
     * @throws PortFailure 저장소 조회에 실패한 경우
     */
    Optional<TermsText> translation(UUID termsVersionId, SupportedLocale locale);
}
