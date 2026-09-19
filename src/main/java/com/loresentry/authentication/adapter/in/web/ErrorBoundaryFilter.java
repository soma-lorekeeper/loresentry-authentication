package com.loresentry.authentication.adapter.in.web;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ErrorBoundaryFilter extends OncePerRequestFilter {
    private final JsonMapper mapper;
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try { chain.doFilter(request, response); }
        catch (Exception failure) {
            var mapped = ErrorResponses.response(failure, request);
            if (response.isCommitted()) throw new ServletException("Response processing failed");
            response.resetBuffer(); response.setStatus(mapped.getStatusCode().value());
            response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            mapper.writeValue(response.getOutputStream(), mapped.getBody());
        }
    }
}
