package com.loresentry.authentication.adapter.out.google;

import com.loresentry.authentication.application.port.out.OAuthStateStore;
import com.loresentry.authentication.application.port.out.OidcClient;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.domain.OAuthSecrets;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Spring Security의 OIDC 처리기를 사용해 Google 인증 코드를 교환하고 신원을 검증한다.
 *
 * <p>생성 시 클라이언트 등록 정보와 검증기를 구성한다. 로그인별 값은 저장된 State에서 받아 요청을 만들며, ID 토큰의 서명·클레임·nonce 검증 후 신원을
 * 반환한다. UserInfo API는 호출하지 않는다.
 */
public final class GoogleOidcClient implements OidcClient, AutoCloseable {
    public record Endpoints(String authorization, String token, String jwks) {}

    public static final Endpoints GOOGLE =
            new Endpoints(
                    "https://accounts.google.com/o/oauth2/v2/auth",
                    "https://oauth2.googleapis.com/token",
                    "https://www.googleapis.com/oauth2/v3/certs");

    private static final String REGISTRATION_ID = "google";
    private static final String ISSUER_URI = "https://accounts.google.com";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final int EXCHANGE_TIMEOUT_SECONDS = 8;

    private final ClientRegistration registration;
    private final HttpClient httpClient;
    private final OidcAuthorizationCodeAuthenticationProvider authenticationProvider;
    private final ExecutorService exchangeExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public GoogleOidcClient(GoogleSettings settings, Clock clock) {
        this(settings, clock, GOOGLE);
    }

    /**
     * 지정한 공급자 엔드포인트를 사용하는 클라이언트를 구성한다.
     *
     * <p>엔드포인트 교체는 통제된 HTTP 통합 테스트용이다. 운영 설정에서는 GOOGLE을 사용한다.
     *
     * @param settings 검증된 클라이언트 설정
     * @param clock ID 토큰 시각 검증에 사용할 시계
     * @param endpoints 인증, 토큰 교환 및 JWK 조회 주소
     */
    public GoogleOidcClient(GoogleSettings settings, Clock clock, Endpoints endpoints) {
        registration = createClientRegistration(settings, endpoints);
        httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        authenticationProvider =
                createAuthenticationProvider(registration, requestFactory, endpoints, clock);
    }

    @Override
    public Settings settings() {
        return new Settings(
                REGISTRATION_ID, registration.getClientId(), registration.getRedirectUri());
    }

    @Override
    public URI authorizationUrl(OAuthStateStore.State state) {
        var authorizationRequest = createAuthorizationRequest(state);
        return URI.create(authorizationRequest.getAuthorizationRequestUri());
    }

    /**
     * {@inheritDoc}
     *
     * <p>코드 교환과 신원 검증 결과를 최대 8초 동안 기다린다. 타임아웃 시 작업을 취소하지만 공급자에서 인증 코드가 이미 사용되었을 수 있으므로 자동 재시도하지
     * 않는다.
     */
    @Override
    public Identity exchange(String code, OAuthStateStore.State state) {
        validateExchangeState(state);
        var exchangeTask = exchangeExecutor.submit(() -> exchangeAndVerifyIdentity(code, state));
        try {
            return exchangeTask.get(EXCHANGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException failure) {
            exchangeTask.cancel(true);
            throw failure(PortFailure.Kind.UNAVAILABLE);
        } catch (InterruptedException failure) {
            exchangeTask.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(PortFailure.Kind.UNAVAILABLE);
        } catch (ExecutionException failure) {
            throw translateExchangeFailure(failure.getCause());
        }
    }

    @Override
    public void close() {
        exchangeExecutor.shutdownNow();
        httpClient.shutdownNow();
    }

    private static ClientRegistration createClientRegistration(
            GoogleSettings settings, Endpoints endpoints) {
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(settings.clientId())
                .clientSecret(settings.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(settings.redirectUri())
                .scope("openid", "email", "profile")
                .authorizationUri(endpoints.authorization())
                .tokenUri(endpoints.token())
                .jwkSetUri(endpoints.jwks())
                .issuerUri(ISSUER_URI)
                .userNameAttributeName("sub")
                .build();
    }

    private static OidcAuthorizationCodeAuthenticationProvider createAuthenticationProvider(
            ClientRegistration registration,
            JdkClientHttpRequestFactory requestFactory,
            Endpoints endpoints,
            Clock clock) {
        var tokenClient = createTokenClient(requestFactory);
        var authenticationProvider =
                new OidcAuthorizationCodeAuthenticationProvider(
                        tokenClient,
                        request -> new DefaultOidcUser(List.of(), request.getIdToken()));
        var idTokenDecoder = createIdTokenDecoder(registration, requestFactory, endpoints, clock);
        authenticationProvider.setJwtDecoderFactory(ignored -> idTokenDecoder);
        return authenticationProvider;
    }

    private static RestClientAuthorizationCodeTokenResponseClient createTokenClient(
            JdkClientHttpRequestFactory requestFactory) {
        var restClient =
                RestClient.builder()
                        .requestFactory(requestFactory)
                        .configureMessageConverters(
                                converters -> {
                                    converters.addCustomConverter(new FormHttpMessageConverter());
                                    converters.addCustomConverter(
                                            new OAuth2AccessTokenResponseHttpMessageConverter());
                                })
                        .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                        .build();
        var tokenClient = new RestClientAuthorizationCodeTokenResponseClient();
        tokenClient.setRestClient(restClient);
        return tokenClient;
    }

    private static NimbusJwtDecoder createIdTokenDecoder(
            ClientRegistration registration,
            JdkClientHttpRequestFactory requestFactory,
            Endpoints endpoints,
            Clock clock) {
        var decoder =
                NimbusJwtDecoder.withJwkSetUri(endpoints.jwks())
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .restOperations(new RestTemplate(requestFactory))
                        .build();
        var identityValidator = new OidcIdTokenValidator(registration);
        identityValidator.setClock(clock);
        var timestampValidator = new JwtTimestampValidator();
        timestampValidator.setClock(clock);
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(timestampValidator, identityValidator));
        decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
        return decoder;
    }

    private OAuth2AuthorizationRequest createAuthorizationRequest(OAuthStateStore.State state) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(registration.getRedirectUri())
                .scopes(registration.getScopes())
                .state(state.state())
                .attributes(
                        attributes -> {
                            attributes.put("nonce", state.nonce());
                            attributes.put("code_verifier", state.codeVerifier());
                        })
                .additionalParameters(
                        parameters -> {
                            parameters.put("nonce", OAuthSecrets.hash(state.nonce()));
                            parameters.put(
                                    "code_challenge", OAuthSecrets.hash(state.codeVerifier()));
                            parameters.put("code_challenge_method", "S256");
                            parameters.put("response_mode", "query");
                        })
                .build();
    }

    private void validateExchangeState(OAuthStateStore.State state) {
        if (!REGISTRATION_ID.equals(state.registrationId())
                || !registration.getClientId().equals(state.clientId())
                || !registration.getRedirectUri().equals(state.redirectUri())
                || !OAuthSecrets.valid(state.nonce())
                || !OAuthSecrets.valid(state.codeVerifier())) {
            throw failure(PortFailure.Kind.INVALID_IDENTITY);
        }
    }

    private Identity exchangeAndVerifyIdentity(String code, OAuthStateStore.State state) {
        var authorizationResponse =
                OAuth2AuthorizationResponse.success(code)
                        .state(state.state())
                        .redirectUri(registration.getRedirectUri())
                        .build();
        var authorizationExchange =
                new OAuth2AuthorizationExchange(
                        createAuthorizationRequest(state), authorizationResponse);
        var authenticationRequest =
                new OAuth2LoginAuthenticationToken(registration, authorizationExchange);
        var authenticationResult =
                (OAuth2LoginAuthenticationToken)
                        authenticationProvider.authenticate(authenticationRequest);
        if (authenticationResult == null) {
            throw failure(PortFailure.Kind.INVALID_IDENTITY);
        }
        var principal = authenticationResult.getPrincipal();
        String subject = principal.getAttribute("sub");
        if (subject == null || subject.isBlank()) {
            throw failure(PortFailure.Kind.INVALID_IDENTITY);
        }
        return new Identity(
                REGISTRATION_ID,
                subject,
                principal.getAttribute("name"),
                principal.getAttribute("email"));
    }

    private PortFailure translateExchangeFailure(Throwable cause) {
        if (cause instanceof PortFailure portFailure) {
            return portFailure;
        }
        for (var nested = cause; nested != null; nested = nested.getCause()) {
            if (nested instanceof IOException || nested instanceof RestClientException) {
                return failure(PortFailure.Kind.UNAVAILABLE);
            }
        }
        return failure(PortFailure.Kind.INVALID_IDENTITY);
    }

    private PortFailure failure(PortFailure.Kind kind) {
        return new PortFailure(kind, PortFailure.Execution.UNKNOWN, false);
    }
}
