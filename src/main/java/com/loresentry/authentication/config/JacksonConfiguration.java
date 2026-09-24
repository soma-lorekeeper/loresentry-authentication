package com.loresentry.authentication.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
public class JacksonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer strictRequestJson() {
        // Preserve the former JsonNode boundary: unknown fields and non-string credentials are
        // invalid.
        return builder ->
                builder.enable(
                                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                                DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
                        .withCoercionConfig(
                                LogicalType.Textual,
                                config ->
                                        config.setCoercion(
                                                        CoercionInputShape.Integer,
                                                        CoercionAction.Fail)
                                                .setCoercion(
                                                        CoercionInputShape.Float,
                                                        CoercionAction.Fail)
                                                .setCoercion(
                                                        CoercionInputShape.Boolean,
                                                        CoercionAction.Fail));
    }
}
