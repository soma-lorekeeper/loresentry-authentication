package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import static com.loresentry.authentication.application.port.in.AuthFailure.Consumption.*;
import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;

public final class LoginService implements LoginUseCase {
    private final OAuthRequests requests;
    private final OidcClient provider;
    private final RegisterIdentityUseCase accounts;
    private final JwtTokens jwt;
    private final RefreshTokenStore refresh;
    public LoginService(OAuthRequests requests, OidcClient provider, RegisterIdentityUseCase accounts, JwtTokens jwt, RefreshTokenStore refresh) {
        this.requests = requests; this.provider = provider; this.accounts = accounts; this.jwt = jwt; this.refresh = refresh;
    }
    public PreparedLogin prepare() { return requests.prepare(); }
    public LoginResult callback(Callback command) {
        OAuthStateStore.State state;
        try { state = requests.consume(command); }
        catch (AuthFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new AuthFailure(INTERNAL_ERROR, UNKNOWN, failure); }
        if (command.error() != null && !command.error().isBlank()) throw new AuthFailure(OAUTH_LOGIN_DENIED, CONSUMED);
        try {
            var identity = provider.exchange(command.code(), state);
            var user = accounts.register(identity); // Account port returns only after the DB transaction commits.
            var issued = jwt.issue(user.id());
            refresh.save(issued.refreshJti(), user.id(), issued.tokens().refreshExpiresAt());
            return new LoginResult(issued.tokens(), CONSUMED);
        } catch (PortFailure failure) {
            throw new AuthFailure(failure.kind() == PortFailure.Kind.INVALID_IDENTITY ? OAUTH_IDENTITY_INVALID : LOGIN_UNAVAILABLE, CONSUMED);
        } catch (AuthFailure failure) { throw new AuthFailure(failure.reason(), CONSUMED); }
        catch (RuntimeException failure) { throw new AuthFailure(INTERNAL_ERROR, CONSUMED, failure); }
    }
}
