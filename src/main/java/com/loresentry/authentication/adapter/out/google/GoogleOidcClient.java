package com.loresentry.authentication.adapter.out.google;

import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.OAuthSecrets;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.*;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.web.client.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class GoogleOidcClient implements OidcClient, AutoCloseable {
    public record Endpoints(String authorization, String token, String jwks) {}
    public static final Endpoints GOOGLE = new Endpoints("https://accounts.google.com/o/oauth2/v2/auth",
        "https://oauth2.googleapis.com/token", "https://www.googleapis.com/oauth2/v3/certs");
    private final ClientRegistration registration;
    private final HttpClient http;
    private final OidcAuthorizationCodeAuthenticationProvider authentication;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    public GoogleOidcClient(GoogleSettings settings, Clock clock) { this(settings, clock, GOOGLE); }
    // The endpoint override is for controlled HTTP integration tests; production configuration always uses GOOGLE.
    public GoogleOidcClient(GoogleSettings settings, Clock clock, Endpoints endpoints) {
        registration = ClientRegistration.withRegistrationId("google").clientId(settings.clientId()).clientSecret(settings.clientSecret())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(settings.redirectUri()).scope("openid", "email", "profile").authorizationUri(endpoints.authorization())
            .tokenUri(endpoints.token()).jwkSetUri(endpoints.jwks()).issuerUri("https://accounts.google.com").userNameAttributeName("sub").build();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http); factory.setReadTimeout(Duration.ofSeconds(3));
        var client = RestClient.builder().requestFactory(factory).configureMessageConverters(converters -> {
            converters.addCustomConverter(new FormHttpMessageConverter());
            converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
        }).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build();
        var tokenClient = new RestClientAuthorizationCodeTokenResponseClient(); tokenClient.setRestClient(client);
        authentication = new OidcAuthorizationCodeAuthenticationProvider(tokenClient,
            request -> new DefaultOidcUser(List.of(), request.getIdToken()));
        var decoder = NimbusJwtDecoder.withJwkSetUri(endpoints.jwks()).jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(new RestTemplate(factory)).build();
        var validator = new OidcIdTokenValidator(registration); validator.setClock(clock);
        var timestamps = new JwtTimestampValidator(); timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, validator));
        decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
        authentication.setJwtDecoderFactory(ignored -> decoder);
    }
    public Settings settings() { return new Settings("google", registration.getClientId(), registration.getRedirectUri()); }
    public URI authorizationUrl(OAuthStateStore.State state) { return URI.create(request(state).getAuthorizationRequestUri()); }
    private OAuth2AuthorizationRequest request(OAuthStateStore.State state) {
        return OAuth2AuthorizationRequest.authorizationCode().authorizationUri(registration.getProviderDetails().getAuthorizationUri())
            .clientId(registration.getClientId()).redirectUri(registration.getRedirectUri()).scopes(registration.getScopes()).state(state.state())
            .attributes(attributes -> { attributes.put("nonce", state.nonce()); attributes.put("code_verifier", state.codeVerifier()); })
            .additionalParameters(parameters -> {
                parameters.put("nonce", OAuthSecrets.hash(state.nonce())); parameters.put("code_challenge", OAuthSecrets.hash(state.codeVerifier()));
                parameters.put("code_challenge_method", "S256"); parameters.put("response_mode", "query");
            }).build();
    }
    public Identity exchange(String code, OAuthStateStore.State state) {
        if (!"google".equals(state.registrationId()) || !registration.getClientId().equals(state.clientId())
            || !registration.getRedirectUri().equals(state.redirectUri()) || !OAuthSecrets.valid(state.nonce()) || !OAuthSecrets.valid(state.codeVerifier()))
            throw failure(PortFailure.Kind.INVALID_IDENTITY);
        var task = requests.submit(() -> {
            var response = OAuth2AuthorizationResponse.success(code).state(state.state()).redirectUri(registration.getRedirectUri()).build();
            var input = new OAuth2LoginAuthenticationToken(registration, new OAuth2AuthorizationExchange(request(state), response));
            var result = (OAuth2LoginAuthenticationToken) authentication.authenticate(input);
            if (result == null) throw failure(PortFailure.Kind.INVALID_IDENTITY);
            var principal = result.getPrincipal();
            String subject = principal.getAttribute("sub");
            if (subject == null || subject.isBlank()) throw failure(PortFailure.Kind.INVALID_IDENTITY);
            return new Identity("google", subject, principal.getAttribute("name"), principal.getAttribute("email"));
        });
        try { return task.get(8, TimeUnit.SECONDS); }
        catch (TimeoutException e) { task.cancel(true); throw failure(PortFailure.Kind.UNAVAILABLE); }
        catch (InterruptedException e) { task.cancel(true); Thread.currentThread().interrupt(); throw failure(PortFailure.Kind.UNAVAILABLE); }
        catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof PortFailure failure) throw failure;
            for (var nested = cause; nested != null; nested = nested.getCause())
                if (nested instanceof java.io.IOException || nested instanceof RestClientException)
                    throw failure(PortFailure.Kind.UNAVAILABLE);
            throw failure(PortFailure.Kind.INVALID_IDENTITY);
        }
    }
    private PortFailure failure(PortFailure.Kind kind) { return new PortFailure(kind, PortFailure.Execution.UNKNOWN, false); }
    public void close() { requests.shutdownNow(); http.shutdownNow(); }
}
