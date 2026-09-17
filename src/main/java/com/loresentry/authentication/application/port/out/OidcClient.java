package com.loresentry.authentication.application.port.out;

import java.net.URI;

public interface OidcClient {
    record Settings(String registrationId, String clientId, String redirectUri) {}
    record Identity(String provider, String subject, String name, String email) {}
    Settings settings();
    URI authorizationUrl(OAuthStateStore.State state);
    Identity exchange(String code, OAuthStateStore.State state);
}
