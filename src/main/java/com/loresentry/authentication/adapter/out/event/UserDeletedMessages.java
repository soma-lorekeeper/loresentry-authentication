package com.loresentry.authentication.adapter.out.event;

import com.loresentry.authentication.adapter.out.outbox.OutboxRecord;
import com.loresentry.authentication.adapter.out.outbox.OutboxRepository;
import java.util.UUID;

/**
 * {@code auth.user.deleted.v1} 로 갈 메시지를 만든다. payload 의 모양은 {@link UserDeleted} 가 정하고, 이 클래스는 그것을 어떤
 * 종류·aggregate·key 의 메시지로 낼지 정한다. 계정을 지우는 쪽은 지운 사용자의 id 만 넘기고, 받은 메시지를 {@link
 * OutboxRepository#append} 에 준다.
 *
 * <p>DB 를 읽을 일이 없어 bean 으로 만들지 않고 static 메서드만 둔다.
 */
public final class UserDeletedMessages {

    /**
     * header {@code ce_type}. {@code OutboxMessages.topicOf} 가 이 값을 {@code auth.user.deleted.v1} 로
     * 보낸다.
     */
    private static final String USER_DELETED = "UserDeleted";

    private UserDeletedMessages() {}

    /**
     * 계정 하나가 삭제됐다는 메시지. 종류를 뺀 세 칸이 모두 그 사용자의 id 다.
     *
     * <ul>
     *   <li>aggregate 는 header {@code ce_subject} 가 된다. content 는 이 값이 payload 의 {@code user_id} 와
     *       다르면 메시지를 거부한다.
     *   <li>key 는 계약이 정한 {@code user_id} 다. 같은 사용자의 이벤트는 같은 partition 으로 간다.
     * </ul>
     *
     * @param userId 삭제한 사용자의 id
     */
    public static OutboxRecord userDeleted(UUID userId) {
        return new OutboxRecord(USER_DELETED, userId, userId.toString(), new UserDeleted(userId));
    }
}
