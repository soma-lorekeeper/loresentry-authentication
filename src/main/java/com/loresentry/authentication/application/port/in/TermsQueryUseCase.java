package com.loresentry.authentication.application.port.in;

import com.loresentry.authentication.domain.TermsText;
import com.loresentry.authentication.domain.TermsVersion;
import java.time.Instant;

public interface TermsQueryUseCase {
    /**
     * 현재 약관과 대기 만료다.
     *
     * @param terms 동의 대상 약관 버전
     * @param text 응답할 제목과 본문. 번역을 사용해도 동의 대상 버전은 terms와 같음
     * @param expiresAt 동의 대기의 절대 만료 시각
     */
    record TermsView(TermsVersion terms, TermsText text, Instant expiresAt) {}

    /**
     * 동의 대기의 현재 약관을 조회하고 대기의 대상 버전을 만료 연장 없이 갱신한다.
     *
     * @param consentRequestId 동의 대기 식별자 원문
     * @param locale 요청 언어. null·지원하지 않는 값이거나 해당 번역이 없으면 원문을 반환
     * @return 현재 약관, 응답 언어의 본문과 대기 만료
     * @throws AuthFailure 대기가 유효하지 않거나 적용 약관이 없거나 저장소 조회에 실패한 경우
     */
    TermsView query(String consentRequestId, String locale);
}
