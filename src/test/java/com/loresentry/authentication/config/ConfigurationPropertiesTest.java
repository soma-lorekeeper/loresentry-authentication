package com.loresentry.authentication.config;

import com.loresentry.authentication.adapter.out.google.GoogleSettings;
import com.loresentry.authentication.config.properties.GoogleProperties;
import com.loresentry.authentication.config.properties.JwtProperties;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({JwtProperties.class, GoogleProperties.class})
    static class PropertiesConfiguration {}

    private final Map<String, String> values = Map.of(
            "auth.jwt.private-key-base64", "test-private-key",
            "auth.jwt.public-key-path", "/test/public.pem",
            "auth.jwt.key-id", "test-key-id",
            "auth.google.client-id", "test-client",
            "auth.google.client-secret", "test-client-secret",
            "auth.google.redirect-uri", "https://api.loresentry.com/auth/oauth/google/callback");

    @Test
    void existingEnvironmentVariablesBindThroughApplicationYaml() throws IOException {
        var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        Map<String, Object> environment = Map.of(
                "AUTH_JWT_PRIVATE_KEY_BASE64", values.get("auth.jwt.private-key-base64"),
                "AUTH_JWT_PUBLIC_KEY_PATH", values.get("auth.jwt.public-key-path"),
                "AUTH_JWT_KEY_ID", values.get("auth.jwt.key-id"),
                "AUTH_GOOGLE_CLIENT_ID", values.get("auth.google.client-id"),
                "AUTH_GOOGLE_CLIENT_SECRET", values.get("auth.google.client-secret"),
                "AUTH_GOOGLE_REDIRECT_URI", values.get("auth.google.redirect-uri"));
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.addFirst(new SystemEnvironmentPropertySource("test-environment", environment));
                    yaml.forEach(sources::addLast);
                }).run(context -> {
                    assertThat(context).hasNotFailed();
                    var jwt = context.getBean(JwtProperties.class);
                    assertThat(jwt.privateKeyBase64()).isEqualTo(values.get("auth.jwt.private-key-base64"));
                    assertThat(jwt.publicKeyPath()).isEqualTo(values.get("auth.jwt.public-key-path"));
                    assertThat(jwt.keyId()).isEqualTo(values.get("auth.jwt.key-id"));
                    assertThat(jwt.toString()).isEqualTo("JwtProperties[REDACTED]");
                    var google = context.getBean(GoogleProperties.class);
                    assertThat(google.clientId()).isEqualTo(values.get("auth.google.client-id"));
                    assertThat(google.clientSecret()).isEqualTo(values.get("auth.google.client-secret"));
                    assertThat(google.redirectUri()).isEqualTo(values.get("auth.google.redirect-uri"));
                    assertThat(google.toString()).isEqualTo("GoogleProperties[REDACTED]");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth.jwt.private-key-base64", "auth.jwt.public-key-path", "auth.jwt.key-id",
            "auth.google.client-id", "auth.google.client-secret", "auth.google.redirect-uri"})
    void missingOrBlankRequiredSettingFailsDuringBinding(String name) {
        var properties = new LinkedHashMap<>(values);
        properties.remove(name);
        runner(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
        });
        properties.put(name, "   ");
        runner(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @Test
    void localProfileStillControlsWhetherLoopbackCallbackIsAllowed() {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(GoogleConfiguration.class, CoreConfiguration.class)
                .withPropertyValues("auth.google.client-id=test-client", "auth.google.client-secret=test-secret",
                        "auth.google.redirect-uri=http://localhost:3000/auth/oauth/google/callback");
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("Invalid Google OAuth configuration");
        });
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GoogleSettings.class).redirectUri())
                            .isEqualTo("http://localhost:3000/auth/oauth/google/callback");
                });
    }

    private ApplicationContextRunner runner(Map<String, String> properties) {
        return new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(properties.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
    }
}
