package com.loresentry.authentication.application.port.in;

import java.util.Objects;

/** HTTP 상태나 프레임워크 타입 대신 애플리케이션의 실패 이유와 요청 소모 여부를 전달한다. */
public class AuthFailure extends RuntimeException {
    public enum Reason {
        INVALID_REQUEST,
        INVALID_SESSION_ID,
        INVALID_DISPLAY_NAME,
        OAUTH_REQUEST_INVALID,
        OAUTH_LOGIN_DENIED,
        OAUTH_IDENTITY_INVALID,
        USER_CONTEXT_REQUIRED,
        USER_NOT_FOUND,
        LOGIN_UNAVAILABLE,
        REVOCATION_UNCONFIRMED,
        ACCOUNT_UNAVAILABLE,
        INTERNAL_ERROR
    }

    /** 현재 콜백 처리에서 로그인 요청을 소모했는지 나타낸다. */
    public enum Consumption {
        /** 현재 처리에서는 요청을 소모하지 않았다. 요청이 여전히 존재한다는 뜻은 아니다. */
        NOT_CONSUMED,
        /** 요청 삭제가 실행되었다. 후속 로그인 처리의 성공 여부와는 별개다. */
        CONSUMED,
        /** 저장소 응답 실패 등으로 소모 여부를 확인하지 못했다. */
        UNKNOWN
    }

    private final Reason reason;
    private final Consumption consumption;

    public AuthFailure(Reason reason) {
        this(reason, null);
    }

    public AuthFailure(Reason reason, Consumption consumption) {
        this(reason, consumption, null);
    }

    public AuthFailure(Reason reason, Consumption consumption, Throwable cause) {
        super(Objects.requireNonNull(reason).name(), cause);
        this.reason = reason;
        this.consumption = consumption;
    }

    public Reason reason() {
        return reason;
    }

    /**
     * 콜백 실패 시 로그인 요청의 소모 여부를 반환한다.
     *
     * @return 콜백 외 실패는 null, 소모 여부를 확인하지 못한 콜백 실패는 UNKNOWN
     */
    public Consumption consumption() {
        return consumption;
    }
}
