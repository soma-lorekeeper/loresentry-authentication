package com.loresentry.authentication.adapter.in.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

// RequestLogFilter(HIGHEST_PRECEDENCE + 2) 안쪽에서 돈다. 그래서 이 필터가 쓴 오류 응답도 접근 로그의 결과 줄(request
// completed)에
// 남고, 오류를 기록할 때 요청의 trace가 붙는다(LoggingConfiguration).
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
@RequiredArgsConstructor
public class ErrorBoundaryFilter extends OncePerRequestFilter {
    private final JsonMapper mapper;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (Exception failure) {
            var mapped = ErrorResponses.response(failure, request);
            // 응답을 이미 보내기 시작해 오류 본문을 쓸 수 없다. 원래 예외를 원인으로 실어, 처리되지 않은 예외 줄(LogFlow.request)의
            // 메시지 없는 스택에 원인이 남게 한다
            if (response.isCommitted())
                throw new ServletException("Response processing failed", failure);
            response.resetBuffer();
            response.setStatus(mapped.getStatusCode().value());
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            mapper.writeValue(response.getOutputStream(), mapped.getBody());
        }
    }
}
