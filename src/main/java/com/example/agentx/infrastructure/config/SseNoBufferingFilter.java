package com.example.agentx.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 为SSE接口添加禁用代理缓冲的响应头，避免流式响应被中间层攒到结束后一次性返回。 */
@Component
public class SseNoBufferingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isSseRequest(request)) {
            request.setAttribute(GlobalExceptionHandler.SSE_REQUEST_ATTRIBUTE, Boolean.TRUE);
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("X-Accel-Buffering", "no");
        }
        filterChain.doFilter(request, response);
    }

    private boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/event-stream")) {
            return true;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        return uri.endsWith("/agents/sessions/chat")
                || uri.endsWith("/agents/sessions/preview")
                || uri.endsWith("/rag/search/stream-chat")
                || uri.contains("/rag/search/user-rag/") && uri.endsWith("/stream-chat")
                || uri.contains("/widget/") && uri.endsWith("/chat");
    }
}
