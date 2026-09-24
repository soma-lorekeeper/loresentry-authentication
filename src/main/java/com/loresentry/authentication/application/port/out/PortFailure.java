package com.loresentry.authentication.application.port.out;

import java.util.Objects;

/**
 * 외부 연동 실패의 종류, 실행 여부와 재시도 가능성을 전달한다.
 *
 * <p>타임아웃만으로 미실행을 단정하지 않는다. retryable이 true여도 RT 소모처럼 재시도를 금지한 작업은 다시 실행하면 안 된다. 공급자 응답이나 자격 증명을 예외
 * 메시지에 포함하지 않는다.
 */
public class PortFailure extends RuntimeException {
    public enum Kind {
        UNAVAILABLE,
        INVALID_DATA,
        IDENTITY_ALREADY_REGISTERED,
        INVALID_IDENTITY,
        INVALID_TOKEN
    }

    /** 실패한 외부 작업이 실행되었는지에 대한 확인 결과다. */
    public enum Execution {
        /** 작업이 실행되지 않았음을 확인했다. */
        NOT_EXECUTED,
        /** 작업이 실행되었음을 확인했다. 반환 데이터 해석은 실패했을 수 있다. */
        EXECUTED,
        /** 실행 여부를 확인할 수 없다. */
        UNKNOWN
    }

    private final Kind kind;
    private final Execution execution;
    private final boolean retryable;

    public PortFailure(Kind kind, Execution execution, boolean retryable) {
        // Raw SQL, provider responses and credentials never become exception messages.
        super(Objects.requireNonNull(kind).name());
        this.kind = kind;
        this.execution = Objects.requireNonNull(execution);
        this.retryable = retryable;
    }

    public Kind kind() {
        return kind;
    }

    public Execution execution() {
        return execution;
    }

    public boolean retryable() {
        return retryable;
    }
}
