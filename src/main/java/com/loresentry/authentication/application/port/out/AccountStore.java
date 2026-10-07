package com.loresentry.authentication.application.port.out;

import com.loresentry.authentication.domain.OAuthIdentity;
import com.loresentry.authentication.domain.SupportedLocale;
import com.loresentry.authentication.domain.User;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 서비스 계정과 외부 신원 연결을 저장·조회한다.
 *
 * <p>변경 작업은 커밋이 완료된 뒤 반환한다. 빈 Optional은 조회 대상의 부재이며 저장소 장애를 뜻하지 않는다.
 */
public interface AccountStore {
    record Account(User user, OAuthIdentity identity) {}

    /**
     * 외부 신원에 연결된 계정을 조회한다.
     *
     * @param provider 공급자 식별자
     * @param providerId 공급자 안에서 사용자를 식별하는 subject
     * @return 연결된 계정. 없으면 빈 Optional
     * @throws PortFailure 저장소 조회에 실패한 경우
     */
    Optional<Account> findByIdentity(String provider, String providerId);

    /**
     * 사용자 식별자로 계정과 연결된 외부 신원을 조회한다.
     *
     * @param userId 서비스 사용자 식별자
     * @return 연결된 계정. 없으면 빈 Optional
     * @throws PortFailure 저장소 조회에 실패하거나 외부 신원 연결을 결정할 수 없는 경우
     */
    Optional<Account> findById(UUID userId);

    /**
     * 사용자와 외부 신원 연결을 하나의 트랜잭션으로 생성한다.
     *
     * @param user 생성할 사용자
     * @param identity 사용자에 연결할 외부 신원
     * @return 커밋한 계정
     * @throws PortFailure 동일 외부 신원이 먼저 등록되었거나 저장에 실패한 경우
     */
    Account create(User user, OAuthIdentity identity);

    /**
     * 외부 신원의 이메일과 사용자의 수정 시각을 변경한다.
     *
     * @param provider 공급자 식별자
     * @param providerId 공급자의 사용자 식별자
     * @param email 저장할 이메일
     * @param updatedAt 사용자 수정 시각
     * @return 커밋한 계정
     * @throws PortFailure 대상이 없거나 변경에 실패한 경우
     */
    Account updateEmail(String provider, String providerId, String email, Instant updatedAt);

    /**
     * 사용자의 표시 이름과 수정 시각을 변경한다.
     *
     * @param userId 서비스 사용자 식별자
     * @param displayName 호출자가 도메인 규칙을 검증한 표시 이름
     * @param updatedAt 사용자 수정 시각
     * @return 커밋한 계정. 대상이 없으면 빈 Optional
     * @throws PortFailure 조회 또는 변경에 실패한 경우
     */
    Optional<Account> rename(UUID userId, String displayName, Instant updatedAt);

    /**
     * 사용자의 언어를 변경한다. 저장된 값과 같으면 수정 시각을 유지한다.
     *
     * @param userId 서비스 사용자 식별자
     * @param locale 저장할 언어
     * @param updatedAt 값이 바뀔 때 기록할 사용자 수정 시각
     * @return 커밋한 계정. 대상이 없으면 빈 Optional
     * @throws PortFailure 조회 또는 변경에 실패한 경우
     */
    Optional<Account> changeLocale(UUID userId, SupportedLocale locale, Instant updatedAt);

    /**
     * 온보딩 완료 시각이 비어 있을 때만 기록한다. 이미 완료한 계정은 처음 시각을 유지한다.
     *
     * @param userId 서비스 사용자 식별자
     * @param completedAt 처음 완료로 기록할 시각
     * @return 대상 계정이 있으면 true, 없으면 false
     * @throws PortFailure 변경에 실패한 경우
     */
    boolean completeOnboarding(UUID userId, Instant completedAt);

    /**
     * 사용자의 약관 동의 기록, 외부 신원 연결과 사용자를 하나의 트랜잭션으로 삭제한다.
     *
     * @param userId 서비스 사용자 식별자
     * @return 사용자를 삭제했으면 true, 대상이 없으면 false
     * @throws PortFailure 삭제에 실패한 경우. 트랜잭션이 커밋되지 않았을 수 있다.
     */
    boolean delete(UUID userId);
}
