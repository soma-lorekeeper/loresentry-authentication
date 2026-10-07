package com.loresentry.authentication.application.port.in;

import com.loresentry.authentication.domain.SupportedLocale;
import java.util.UUID;

/** 호출자가 인증한 사용자 식별자로 프로필을 조회·수정하고 언어 저장, 온보딩 완료와 탈퇴를 처리하는 진입점이다. */
public interface AccountUseCase {
    /**
     * 서비스 계정과 연결된 OAuth 신원의 이메일을 조회한다.
     *
     * @param userId 호출자가 인증한 사용자 식별자
     * @return 계정 프로필. 공급자가 제공하지 않은 이메일은 null일 수 있음
     * @throws AuthFailure 식별자가 없거나 계정이 없거나 저장소 조회에 실패한 경우
     */
    Profile get(UUID userId);

    /**
     * 표시 이름의 앞뒤 공백을 제거하고 저장한다.
     *
     * @param userId 호출자가 인증한 사용자 식별자
     * @param displayName 공백 제거 후 유니코드 코드 포인트 기준 1~50자인 이름
     * @return 저장이 완료된 계정 프로필
     * @throws AuthFailure 식별자·이름이 유효하지 않거나 계정이 없거나 저장에 실패한 경우
     */
    Profile rename(UUID userId, String displayName);

    /**
     * 계정 언어를 저장한다. 저장된 값과 같으면 수정 시각을 유지한다.
     *
     * @param userId 호출자가 인증한 사용자 식별자
     * @param locale 소문자 언어 코드 ko 또는 en
     * @return 저장이 완료된 계정 프로필
     * @throws AuthFailure 식별자·언어 코드가 유효하지 않거나 계정이 없거나 저장에 실패한 경우
     */
    Profile changeLocale(UUID userId, String locale);

    /**
     * 온보딩 완료를 기록한다. 이미 완료한 계정은 처음 완료 시각을 유지한다.
     *
     * @param userId 호출자가 인증한 사용자 식별자
     * @throws AuthFailure 식별자가 없거나 계정이 없거나 저장에 실패한 경우
     */
    void completeOnboarding(UUID userId);

    /**
     * 사용자의 로그인 세션을 폐기한 뒤 약관 동의 기록·외부 신원 연결·계정을 삭제한다.
     *
     * <p>세션 폐기를 확인하지 못하면 계정을 삭제하지 않는다. 삭제된 계정에 남은 동의 대기는 계정 조회에서 거절되어 세션으로 완료되지 않는다.
     *
     * @param userId 호출자가 인증한 사용자 식별자
     * @throws AuthFailure 식별자가 없거나 계정이 없거나 세션 폐기·삭제에 실패한 경우
     */
    void delete(UUID userId);

    /**
     * 본인 계정 프로필이다.
     *
     * @param locale 계정 언어. 기록한 적이 없으면 null
     */
    record Profile(
            UUID id,
            String displayName,
            String email,
            boolean onboardingCompleted,
            SupportedLocale locale) {}
}
