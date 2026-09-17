package com.loresentry.authentication;

import com.loresentry.authentication.adapter.out.jwt.JwtKeys;
import com.loresentry.authentication.config.JwtConfiguration;
import com.loresentry.authentication.support.TestKeys;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class JwtKeysTest {
    @Test
    void validConfigurationLoadsAtStartupWithoutChangingKid() {
        context(TestKeys.PRIVATE, TestKeys.PUBLIC_FILE.toString(), TestKeys.KID).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JwtKeys.class).kid()).isEqualTo(TestKeys.KID);
            assertThat(context.getBean(JwtKeys.class).toString()).isEqualTo("JwtKeys[REDACTED]");
        });
    }
    @Test
    void replacementConfigurationUsesOnlyNewKeyAndKid() {
        var pair = TestKeys.generate(2048);
        var kid = java.util.UUID.randomUUID().toString();
        context(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()), TestKeys.publicFile(pair).toString(), kid).run(c -> {
            var keys = c.getBean(JwtKeys.class);
            assertThat(keys.kid()).isEqualTo(kid).isNotEqualTo(TestKeys.KID);
            assertThat(keys.publicKey().getEncoded()).isEqualTo(pair.getPublic().getEncoded()).isNotEqualTo(TestKeys.PAIR.getPublic().getEncoded());
        });
    }
    @Test
    void invalidConfigurationFailsStartup() {
        context("", TestKeys.PUBLIC_FILE.toString(), TestKeys.KID).run(c -> assertThat(c).hasFailed());
        context("not-base64", TestKeys.PUBLIC_FILE.toString(), TestKeys.KID).run(c -> assertThat(c).hasFailed());
        context(TestKeys.PRIVATE, "/missing-public-key.pem", TestKeys.KID).run(c -> assertThat(c).hasFailed());
        context(TestKeys.PRIVATE, TestKeys.PUBLIC_FILE.toString(), "invalid-kid").run(c -> assertThat(c).hasFailed());
        var mismatch = TestKeys.publicFile(TestKeys.generate(2048));
        context(TestKeys.PRIVATE, mismatch.toString(), TestKeys.KID).run(c -> assertThat(c).hasFailed());
        var weak = TestKeys.generate(1024);
        context(Base64.getEncoder().encodeToString(weak.getPrivate().getEncoded()), TestKeys.publicFile(weak).toString(), TestKeys.KID)
                .run(c -> assertThat(c).hasFailed());
    }
    private ApplicationContextRunner context(String privateKey, String publicPath, String kid) {
        return new ApplicationContextRunner().withUserConfiguration(JwtConfiguration.class)
                .withPropertyValues("auth.jwt.private-key-base64=" + privateKey,
                        "auth.jwt.public-key-path=" + publicPath, "auth.jwt.key-id=" + kid);
    }
}
