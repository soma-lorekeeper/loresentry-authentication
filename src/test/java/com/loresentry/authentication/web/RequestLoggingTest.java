package com.loresentry.authentication.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loresentry.authentication.support.DatabaseTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * gateway가 부른 요청 하나를 실제 필터 순서(observation → RequestLogFilter → ErrorBoundaryFilter)로 보내 확인한다.
 *
 * <ul>
 *   <li>gateway가 보낸 traceparent를 이어, 이 서비스의 모든 줄이 gateway와 같은 trace ID를 갖는다
 *   <li>에러 본문 trace_id가 그 trace ID다
 *   <li>받은 경로(url.path)와 경로 템플릿(http.route), gateway가 확인한 사용자(X-User-Id)가 모든 줄에 붙는다
 *   <li>실패 이유(우리 오류 코드)는 따로 찍히지 않고 결과 줄(request completed)의 error.type에 붙는다
 *   <li>헬스체크 흐름의 모든 줄에 tags: health가 붙는다
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingTest extends DatabaseTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GATEWAY_TRACE = "0af7651916cd43dd8448eb211c80319c";

    @Autowired MockMvc mvc;

    private static List<JsonNode> lines(CapturedOutput output, String traceId) {
        return output.getOut()
                .lines()
                .filter(line -> line.startsWith("{"))
                .map(JSON::readTree)
                .filter(line -> traceId.equals(line.at("/trace/id").asString()))
                .toList();
    }

    @Test
    void continuesTheGatewayTraceAndLeavesOneResultLine(CapturedOutput output) throws Exception {
        var response =
                mvc.perform(
                                get("/auth/users/me")
                                        .queryParam("locale", "secret-query")
                                        .header(
                                                "traceparent",
                                                "00-" + GATEWAY_TRACE + "-b7ad6b7169203331-01"))
                        .andExpect(status().isUnauthorized())
                        .andReturn()
                        .getResponse();

        assertThat(JSON.readTree(response.getContentAsString()).get("trace_id").asString())
                .isEqualTo(GATEWAY_TRACE);
        var mine = lines(output, GATEWAY_TRACE);
        assertThat(mine)
                .extracting(line -> line.get("message").asString())
                .contains("request received", "request completed");
        assertThat(mine)
                .allSatisfy(
                        line -> {
                            assertThat(line.at("/url/path").asString()).isEqualTo("/auth/users/me");
                            assertThat(line.at("/http/route").asString())
                                    .isEqualTo("/auth/users/me");
                        });
        JsonNode completed =
                mine.stream()
                        .filter(line -> "request completed".equals(line.get("message").asString()))
                        .findFirst()
                        .orElseThrow();
        assertThat(completed.at("/log/level").asString()).isEqualTo("WARN");
        assertThat(completed.at("/http/response/status_code").asLong()).isEqualTo(401L);
        assertThat(completed.at("/error/type").asString()).isEqualTo("USER_CONTEXT_REQUIRED");
        assertThat(completed.at("/service/name").asString()).isEqualTo("authentication-api");
        assertThat(completed.at("/log/layer").asString()).isEqualTo("filter");
        // 쿼리스트링은 어떤 줄에도 남지 않는다
        assertThat(output.getOut()).doesNotContain("secret-query");
    }

    @Test
    void theUserFromTheGatewayIsOnEveryLine(CapturedOutput output) throws Exception {
        String trace = "2af7651916cd43dd8448eb211c80319c";
        UUID user = UUID.randomUUID();
        mvc.perform(
                get("/auth/users/me")
                        .header("X-User-Id", user.toString())
                        .header("traceparent", "00-" + trace + "-b7ad6b7169203331-01"));
        assertThat(lines(output, trace))
                .isNotEmpty()
                .allSatisfy(
                        line ->
                                assertThat(line.at("/user/id").asString())
                                        .isEqualTo(user.toString()));
    }

    @Test
    void healthChecksAreTracedAndEveryLineIsTaggedHealth(CapturedOutput output) throws Exception {
        String trace = "1af7651916cd43dd8448eb211c80319c";
        mvc.perform(get("/health").header("traceparent", "00-" + trace + "-b7ad6b7169203331-01"))
                .andExpect(status().isOk());
        var health = lines(output, trace);
        assertThat(health)
                .extracting(line -> line.get("message").asString())
                .contains("request received", "request completed");
        assertThat(health)
                .allSatisfy(line -> assertThat(line.get("tags").toString()).contains("health"));
    }

    @Test
    void unknownPathsAreNotFoundNotInternalErrors(CapturedOutput output) throws Exception {
        // 처리기가 없는 경로는 500 INTERNAL_ERROR 가 아니라 404 NOT_FOUND 다. 결과 줄은 warn 이고 스택을 남기는 원인 줄이 없다
        String trace = "3af7651916cd43dd8448eb211c80319c";
        var response =
                mvc.perform(
                                get("/auth/no-such-path")
                                        .header(
                                                "traceparent",
                                                "00-" + trace + "-b7ad6b7169203331-01"))
                        .andExpect(status().isNotFound())
                        .andReturn()
                        .getResponse();
        assertThat(JSON.readTree(response.getContentAsString()).get("code").asString())
                .isEqualTo("NOT_FOUND");
        var mine = lines(output, trace);
        JsonNode completed =
                mine.stream()
                        .filter(line -> "request completed".equals(line.get("message").asString()))
                        .findFirst()
                        .orElseThrow();
        assertThat(completed.at("/log/level").asString()).isEqualTo("WARN");
        assertThat(completed.at("/error/type").asString()).isEqualTo("NOT_FOUND");
        assertThat(mine)
                .extracting(line -> line.get("message").asString())
                .doesNotContain("request failed with an internal error");
    }

    @Test
    void theDatabaseHealthBodyHasNoUserNameOrVersion() throws Exception {
        // gateway /health/db 로 로그인한 사용자도 이 본문을 본다. DB 사용자 이름·버전은 싣지 않는다
        var body =
                JSON.readTree(
                        mvc.perform(get("/health/db"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("status", "service", "database");
    }
}
