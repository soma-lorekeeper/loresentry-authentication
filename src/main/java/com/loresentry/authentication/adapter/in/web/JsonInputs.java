package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.application.port.in.AuthFailure;
import tools.jackson.databind.JsonNode;
import java.util.Set;

final class JsonInputs {
    private JsonInputs() {}
    static void object(JsonNode body, Set<String> allowed, boolean callback) {
        if (body == null || !body.isObject() || !allowed.containsAll(body.propertyNames())) throw invalid(callback);
    }
    static String required(JsonNode body, String field, boolean callback) {
        String value = optional(body, field, callback);
        if (value == null || value.isBlank()) throw invalid(callback);
        return value;
    }
    static String optional(JsonNode body, String field, boolean callback) {
        var value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw invalid(callback);
        return value.asString();
    }
    static AuthFailure invalid(boolean callback) {
        return new AuthFailure(AuthFailure.Reason.INVALID_REQUEST, callback ? AuthFailure.Consumption.NOT_CONSUMED : null);
    }
}
