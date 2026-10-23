package com.loresentry.authentication.adapter.out.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * {@code auth.user.deleted.v1} 로 보내는 UserDeleted 의 payload. 계정이 삭제됐음을 알린다.
 *
 * <p>계약의 JSON 은 {@code {"user_id": "…"}} 하나다. 이메일이나 이름 같은 개인정보는 싣지 않는다 — 받는 쪽(content)은 이 id 의 데이터를
 * 지우기만 하면 되고, 메시지는 Kafka 와 outbox 에 며칠 남는다.
 *
 * @param userId 삭제된 사용자의 id
 */
public record UserDeleted(@JsonProperty("user_id") UUID userId) {}
