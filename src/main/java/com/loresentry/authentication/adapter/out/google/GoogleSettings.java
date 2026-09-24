package com.loresentry.authentication.adapter.out.google;

import java.net.URI;

public record GoogleSettings(String clientId, String clientSecret, String redirectUri) {
    public static GoogleSettings validated(
            String id, String secret, String redirect, boolean local) {
        try {
            if (id == null || id.isBlank() || secret == null || secret.isBlank())
                throw new IllegalArgumentException();
            URI uri = URI.create(redirect);
            boolean loopback =
                    "localhost".equals(uri.getHost())
                            || "127.0.0.1".equals(uri.getHost())
                            || "[::1]".equals(uri.getHost());
            boolean production =
                    "https://api.loresentry.com/auth/oauth/google/callback".equals(redirect);
            boolean development =
                    local
                            && loopback
                            && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                            && uri.getUserInfo() == null
                            && uri.getFragment() == null
                            && uri.getQuery() == null
                            && uri.getPath().startsWith("/");
            if (!production && !development) throw new IllegalArgumentException();
            return new GoogleSettings(id, secret, redirect);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid Google OAuth configuration");
        }
    }

    @Override
    public String toString() {
        return "GoogleSettings[REDACTED]";
    }
}
