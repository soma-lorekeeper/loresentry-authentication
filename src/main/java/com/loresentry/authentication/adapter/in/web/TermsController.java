package com.loresentry.authentication.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.authentication.application.port.in.TermsAcceptUseCase;
import com.loresentry.authentication.application.port.in.TermsQueryUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
    private final TermsAcceptUseCase accept;

    record AcceptRequest(
            @NotNull @JsonProperty("consent_request_id") String consentRequestId,
            @NotNull @JsonProperty("terms_version_id") String termsVersionId) {
        @Override
        public String toString() {
            return "AcceptRequest[redacted]";
        }
    }

    record AcceptedResponse(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("expires_at") Instant expiresAt) {
        @Override
        public String toString() {
            return "AcceptedResponse[redacted]";
        }
    }

    @PostMapping("/accept")
    public ResponseEntity<AcceptedResponse> accept(@Valid @RequestBody AcceptRequest request) {
        var result = accept.accept(request.consentRequestId(), request.termsVersionId());
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new AcceptedResponse(result.sessionId().value(), result.expiresAt()));
    }

    record TermsResponse(
            @JsonProperty("terms_version_id") UUID termsVersionId,
            String version,
            String title,
            String content,
            String locale,
            @JsonProperty("effective_at") Instant effectiveAt,
            @JsonProperty("expires_at") Instant expiresAt) {}

    // locale은 응답 언어 선택에만 쓰며 잘못된 값도 오류 없이 원문으로 응답한다.
    @GetMapping
    public ResponseEntity<TermsResponse> get(
            @RequestHeader(value = "X-Consent-Request-Id", required = false) String id,
            @RequestParam(value = "locale", required = false) String locale) {
        var view = query.query(id, locale);
        var terms = view.terms();
        var text = view.text();
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        new TermsResponse(
                                terms.id(),
                                terms.version(),
                                text.title(),
                                text.content(),
                                text.locale().code(),
                                terms.effectiveAt(),
                                view.expiresAt()));
    }
}
