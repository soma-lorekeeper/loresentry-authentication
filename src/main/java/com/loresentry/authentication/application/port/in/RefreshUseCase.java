package com.loresentry.authentication.application.port.in;

/** 저장된 RT를 한 번 소모하여 새 AT·RT로 교체하는 진입점이다. */
public interface RefreshUseCase {
    /**
     * RT를 검증하고 저장된 소유자를 확인한 뒤 새 토큰 쌍을 발급·저장한다.
     *
     * <p>같은 RT의 동시 갱신은 하나만 소모에 성공할 수 있다. 기존 RT를 소모한 뒤 새 RT 저장이 실패해도 기존 RT를 복구하지 않는다. 소모 결과가 불명확한
     * 실패를 단순 미실행으로 취급하면 안 된다.
     *
     * @param refreshToken 갱신에 사용할 RT 문자열
     * @return 새 RT 저장까지 완료한 AT·RT
     * @throws AuthFailure RT가 거부되거나 소모·발급·저장에 실패한 경우. reason으로 실패 단계를 구분
     */
    TokenPair refresh(String refreshToken);
}
