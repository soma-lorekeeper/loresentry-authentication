package com.loresentry.authentication.application.port.in;

import com.loresentry.authentication.application.port.out.OidcClient.Identity;
import com.loresentry.authentication.domain.User;

/** OIDC 어댑터가 검증한 외부 신원을 서비스 계정에 연결한다. */
public interface RegisterIdentityUseCase {
    /**
     * 공급자와 subject로 기존 계정을 찾거나 새 계정을 등록한다.
     *
     * <p>기존 계정의 표시 이름은 유지하고, 비어 있지 않은 새 이메일만 반영한다. 같은 외부 신원의 동시 가입은 먼저 저장된 계정으로 합류한다. 변경이 있으면 DB
     * 커밋이 완료된 뒤 반환한다.
     *
     * @param identity 서명과 클레임 검증을 마친 공급자 신원
     * @return 외부 신원에 연결된 서비스 사용자
     * @throws AuthFailure 신원 필드가 유효하지 않거나 계정 처리에 실패한 경우
     */
    User register(Identity identity);
}
