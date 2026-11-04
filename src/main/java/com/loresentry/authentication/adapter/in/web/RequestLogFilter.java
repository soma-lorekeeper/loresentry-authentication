package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.adapter.out.logging.LogFlow;
import com.loresentry.authentication.logging.AppLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * gateway가 부른 요청 하나의 시작점. 이 필터는 HTTP 값만 꺼내 로깅 팩토리({@link LogFlow#request})에 넘긴다. 로그 객체를 만들어 바인딩하는
 * 일, 접근 로그(request received·request completed), 걸린 시간, 응답 상태로 정하는 레벨, 처리되지 않은 예외 줄은 모두 LogFlow가
 * 한다(LOG_SCHEMA.md 5절 "만들기", "접근 로그").
 *
 * <p>순서: EDOT agent의 Tomcat 계측이 필터보다 먼저 gateway가 보낸 traceparent를 이어 서버 span을 열고, 이 필터(+2)가 그 안에서
 * 돈다. 그래서 이 요청의 모든 줄이 gateway 줄과 같은 trace.id를 갖는다. {@link ErrorBoundaryFilter}(+3)는 이 필터 안쪽이라, 그
 * 필터가 쓰는 오류 응답도 이 필터의 결과 줄에 남는다.
 *
 * <p>꺼내는 값
 *
 * <ul>
 *   <li>이 서비스가 받은 경로({@code url.path}). 쿼리스트링은 남기지 않는다(locale 등과 함께 OAuth 값이 실릴 수 있다)
 *   <li>경로 템플릿({@code http.route}). Spring MVC가 나중에 고를 것을 요청 시작 때 미리 찾는다
 *   <li>메서드({@code http.request.method})
 *   <li>gateway가 확인해 보낸 사용자(X-User-Id)
 *   <li>응답 상태. 필터 체인이 끝난 뒤 LogFlow에 알린다
 * </ul>
 *
 * <p>요청 본문(OAuth code·state, 세션 ID, 동의 ID)은 읽지 않는다.
 */
public final class RequestLogFilter extends OncePerRequestFilter {
    /** Boot 기본 오류 응답(/error)에도 trace_id를 싣기 위해 요청 속성에 남기는 이름. */
    public static final String TRACE_ATTRIBUTE = RequestLogFilter.class.getName() + ".traceId";

    /** gateway가 세션을 확인한 뒤 넣는 사용자 헤더(/auth/users/me/**). AccountController가 읽는 헤더와 같다. */
    private static final String USER_HEADER = "X-User-Id";

    /** 경로 템플릿을 미리 찾는 데 쓰는 컨트롤러 매핑. MVC가 없는 테스트 문맥에서는 비어 있고, 그때는 http.route를 붙이지 않는다. */
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    public RequestLogFilter(ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        LogFlow.request(
                request.getRequestURI(),
                route(request),
                request.getMethod(),
                user(request),
                flow -> {
                    // Boot 기본 오류 응답(/error)에도 trace_id를 싣도록 요청 속성에 남긴다(로그가 아니라 응답 본문 값이다)
                    String traceId = AppLogger.current().traceId();
                    if (traceId != null) request.setAttribute(TRACE_ATTRIBUTE, traceId);
                    chain.doFilter(request, response);
                    // 결과 줄(request completed)의 상태. 레벨은 LogFlow가 이 값으로 정한다
                    flow.status(response.getStatus());
                });
    }

    /**
     * Spring MVC가 이 요청에 고를 경로 템플릿(예: /projects/{projectId}/files/{fileId}). DispatcherServlet이 나중에
     * 같은 매핑으로 똑같이 고르므로 결과가 같다.
     *
     * <p>매칭은 요청 속성(파싱한 경로, 고른 handler·템플릿 등)을 남긴다. 뒤의 DispatcherServlet이 보는 상태가 바뀌지 않도록 매칭 전 속성을
     * 기억해 두었다가 그대로 되돌린다. 맞는 경로가 없거나(404) 매칭 중 예외(405 등)가 나면 null이다.
     */
    private String route(HttpServletRequest request) {
        RequestMappingHandlerMapping mapping = mappings.getIfAvailable();
        if (mapping == null) return null;
        Map<String, Object> before = new HashMap<>();
        for (String name : Collections.list(request.getAttributeNames()))
            before.put(name, request.getAttribute(name));
        try {
            // 경로 패턴 매칭은 파싱한 요청 경로를 요청 속성에서 읽는다. DispatcherServlet도 나중에 다시 파싱한다
            ServletRequestPathUtils.parseAndCache(request);
            if (mapping.getHandler(request) == null) return null;
            return request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
                            instanceof String pattern
                    ? pattern
                    : null;
        } catch (Exception noMatch) {
            return null;
        } finally {
            // 매칭이 새로 만든 속성은 지우고, 바꾸거나 지운 속성은 원래 값으로 되돌린다
            for (String name : Collections.list(request.getAttributeNames())) {
                if (!before.containsKey(name)) request.removeAttribute(name);
            }
            before.forEach(request::setAttribute);
        }
    }

    /**
     * gateway가 보낸 사용자. 값이 하나이고 표준 표기 UUID일 때만 쓴다. 틀린 값은 컨트롤러가 400으로 거절하므로 로그에는 남기지 않는다. 헤더가 없는
     * 요청(헬스체크 등)은 null이다.
     */
    private static UUID user(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders(USER_HEADER));
        if (values.size() != 1 || values.getFirst().length() != 36) return null;
        try {
            return UUID.fromString(values.getFirst());
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }
}
