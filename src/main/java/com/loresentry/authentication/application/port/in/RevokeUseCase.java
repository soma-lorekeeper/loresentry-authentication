package com.loresentry.authentication.application.port.in;

/** 전달받은 RT 하나의 사용 권한을 폐기하는 진입점이다. */
public interface RevokeUseCase {
    /**
     * RT를 검증하고 저장된 사용 권한을 삭제한다.
     *
     * <p>서명 등 검증을 통과한 만료 RT와 이미 삭제된 RT는 정상 처리한다. 다른 RT나 이미 발급한 AT는 폐기하지 않는다. 정상 반환은 폐기가 확인되었거나 해당
     * RT가 이미 만료되었음을 뜻한다.
     *
     * @param refreshToken 폐기할 RT 문자열
     * @throws AuthFailure 유효한 RT가 아니거나 저장소에서 폐기 결과를 확인하지 못한 경우
     */
    void revoke(String refreshToken);
}
