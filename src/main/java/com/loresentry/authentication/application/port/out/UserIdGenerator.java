package com.loresentry.authentication.application.port.out;

import java.util.UUID;

/** 새 서비스 사용자 식별자의 생성 방식을 애플리케이션에서 분리한다. */
public interface UserIdGenerator {
    /**
     * 새 사용자에 사용할 식별자를 생성한다.
     *
     * @return 새 사용자 UUID. DB 저장은 수행하지 않음
     */
    UUID generate();
}
