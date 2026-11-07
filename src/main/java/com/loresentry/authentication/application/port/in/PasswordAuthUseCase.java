package com.loresentry.authentication.application.port.in;

import com.loresentry.authentication.domain.ConsentId;
import com.loresentry.authentication.domain.SessionId;
import java.time.Instant;

public interface PasswordAuthUseCase {
    Result signUp(SignUp command);

    Result login(Login command);

    record SignUp(String loginId, String password, String displayName) {
        @Override
        public String toString() {
            return "SignUp[redacted]";
        }
    }

    record Login(String loginId, String password) {
        @Override
        public String toString() {
            return "Login[redacted]";
        }
    }

    record Result(
            LoginUseCase.LoginStatus status,
            SessionId sessionId,
            ConsentId consentRequestId,
            Instant expiresAt) {
        public Result {
            if (status == null
                    || expiresAt == null
                    || (status == LoginUseCase.LoginStatus.AUTHENTICATED
                            && (sessionId == null || consentRequestId != null))
                    || (status == LoginUseCase.LoginStatus.TERMS_REQUIRED
                            && (consentRequestId == null || sessionId != null)))
                throw new IllegalArgumentException("Invalid password auth result");
        }

        public static Result authenticated(SessionId id, Instant expiresAt) {
            return new Result(LoginUseCase.LoginStatus.AUTHENTICATED, id, null, expiresAt);
        }

        public static Result termsRequired(ConsentId id, Instant expiresAt) {
            return new Result(LoginUseCase.LoginStatus.TERMS_REQUIRED, null, id, expiresAt);
        }

        @Override
        public String toString() {
            return "Result[redacted]";
        }
    }
}
