package com.loresentry.authentication.application.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 사용자별 활성 로그인과 현재 RT를 관리한다. 각 변경은 저장소에서 원자적으로 수행한다. */
public interface SessionStore {
    record Session(UUID sid, UUID refreshJti, Instant refreshExpiresAt) {
        public Session {
            Objects.requireNonNull(sid);
            Objects.requireNonNull(refreshJti);
            Objects.requireNonNull(refreshExpiresAt);
            if (sid.version() != 4
                    || sid.variant() != 2
                    || refreshJti.version() != 4
                    || refreshJti.variant() != 2
                    || refreshExpiresAt.getNano() != 0
                    || refreshExpiresAt.getEpochSecond() <= 0
                    || refreshExpiresAt.getEpochSecond() > 253402300799L) {
                throw new IllegalArgumentException("Invalid session state");
            }
        }
    }

    /** 새 로그인으로 현재 세션을 교체한다. 성공 확인 전에는 토큰을 반환하지 않는다. */
    void replace(UUID userId, Session session);

    /** 현재 sid·RT 식별자·만료가 expected와 일치할 때만 교체한다. 부재·만료·불일치는 false다. */
    boolean rotate(UUID userId, Session expected, Session replacement);

    /**
     * 입력 RT가 만료 전이고 현재 sid가 일치할 때만 세션을 삭제한다. RT 식별자는 비교하지 않는다. 부재·다른 세션·만료 입력도 정상 완료하며, 연결 획득을 포함해
     * 500ms 안에 결과 또는 실패를 반환한다.
     */
    void revoke(UUID userId, UUID sid, Instant tokenExpiresAt);
}
