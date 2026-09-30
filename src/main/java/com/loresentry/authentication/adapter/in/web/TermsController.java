package com.loresentry.authentication.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.authentication.application.port.in.TermsQueryUseCase;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/terms")
@RequiredArgsConstructor
public class TermsController {
    private final TermsQueryUseCase query;

    record TermsResponse(
            @JsonProperty("terms_version_id") UUID termsVersionId,
            String version,
            String title,
            String content,
            @JsonProperty("effective_at") Instant effectiveAt,
            @JsonProperty("expires_at") Instant expiresAt) {}

    @GetMapping
    public ResponseEntity<TermsResponse> get(
            @RequestHeader(value = "X-Consent-Request-Id", required = false) String id) {
        var view = query.query(id);
        var terms = view.terms();
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        new TermsResponse(
                                terms.id(),
                                terms.version(),
                                terms.title(),
                                terms.content(),
                                terms.effectiveAt(),
                                view.expiresAt()));
    }
}
