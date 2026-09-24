package com.loresentry.authentication.application.port.in;

import java.util.UUID;

/** 호출자가 인증한 사용자 식별자로 프로필을 조회·수정하는 진입점이다. */
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

    record Profile(UUID id, String displayName, String email) {}
}
