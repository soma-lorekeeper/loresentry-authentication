package com.loresentry.authentication.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** RT의 jti별로 소유자와 만료 시각을 관리하며 토큰 원문은 저장하지 않는다. */
public interface RefreshTokenStore {
    /**
     * 새 RT의 사용 권한을 만료 시각까지 저장한다.
     *
     * @param jti 새 RT의 고유 식별자
     * @param userId RT 소유자
     * @param expiresAt 현재 시각보다 뒤인 RT 만료 시각
     * @throws PortFailure 만료 시각이 유효하지 않거나 키가 중복되거나 저장을 확인하지 못한 경우
     */
    void save(UUID jti, UUID userId, Instant expiresAt);

    /**
     * RT 소유자를 조회하면서 사용 권한을 원자적으로 삭제한다.
     *
     * <p>갱신 과정에서 한 번만 실행하며 재시도하지 않는다. 결과를 받지 못했더라도 삭제되었을 수 있다.
     *
     * @param jti 소모할 RT 식별자
     * @return 소유자. 키 부재가 확인되면 빈 Optional
     * @throws PortFailure 저장소 호출이나 소유자 데이터 해석에 실패한 경우. execution에 실행 여부를 포함
     */
    Optional<UUID> consume(UUID jti);

    /**
     * RT 사용 권한의 삭제 또는 기존 부재를 확인한다.
     *
     * <p>이미 없는 키도 정상 처리한다. 연결 획득을 포함하여 500ms 이내에 결과를 반환하거나 실패를 보고해야 한다. 실패하더라도 삭제가 실행되었을 수 있다.
     *
     * @param jti 폐기할 RT 식별자
     * @throws PortFailure 삭제 또는 부재를 확인하지 못한 경우
     */
    void delete(UUID jti);
}
