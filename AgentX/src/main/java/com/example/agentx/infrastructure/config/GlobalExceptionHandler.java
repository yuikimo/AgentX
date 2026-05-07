package com.example.agentx.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.example.agentx.application.conversation.util.ChatErrorResponseFactory;
import com.example.agentx.infrastructure.exception.BusinessException;
import com.example.agentx.infrastructure.exception.EntityNotFoundException;
import com.example.agentx.infrastructure.exception.ParamValidationException;
import com.example.agentx.infrastructure.utils.JsonUtils;
import com.example.agentx.interfaces.api.common.Result;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 全局异常处理器 用于捕获应用中的各种异常，并将其转换为统一的API响应格式 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    public static final String SSE_REQUEST_ATTRIBUTE = "com.example.agentx.sse.request";

    /** 处理业务异常 */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object handleBusinessException(BusinessException e, HttpServletRequest request, HttpServletResponse response) {
        logger.error("业务异常: {}, URL: {}", e.getMessage(), request.getRequestURL(), e);
        if (isSseRequest(request)) {
            writeSseErrorResponse(response, e);
            return null;
        }
        return Result.error(400, e.getMessage());
    }

    /** 处理参数校验异常 */
    @ExceptionHandler(ParamValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object handleParamValidationException(ParamValidationException e, HttpServletRequest request,
            HttpServletResponse response) {
        logger.error("参数校验异常: {}, URL: {}", e.getMessage(), request.getRequestURL(), e);
        if (isSseRequest(request)) {
            writeSseErrorResponse(response, e);
            return null;
        }
        return Result.badRequest(e.getMessage());
    }

    /** 处理实体未找到异常 */
    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Object handleEntityNotFoundException(EntityNotFoundException e, HttpServletRequest request,
            HttpServletResponse response) {
        logger.error("实体未找到异常: {}, URL: {}", e.getMessage(), request.getRequestURL(), e);
        if (isSseRequest(request)) {
            writeSseErrorResponse(response, e);
            return null;
        }
        return Result.notFound(e.getMessage());
    }

    /** 处理方法参数校验异常（@Valid注解导致的异常） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object handleMethodArgumentNotValidException(MethodArgumentNotValidException e,
            HttpServletRequest request, HttpServletResponse response) {
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors();
        String errorMessage = fieldErrors.stream().map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining(", "));

        logger.error("方法参数校验异常: {}, URL: {}", errorMessage, request.getRequestURL(), e);
        if (isSseRequest(request)) {
            writeSseErrorResponse(response, e);
            return null;
        }
        return Result.badRequest(errorMessage);
    }

    /** 处理表单绑定异常 */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object handleBindException(BindException e, HttpServletRequest request, HttpServletResponse response) {
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors();
        String errorMessage = fieldErrors.stream().map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        logger.error("表单绑定异常: {}, URL: {}", errorMessage, request.getRequestURL(), e);
        if (isSseRequest(request)) {
            writeSseErrorResponse(response, e);
            return null;
        }
        return Result.badRequest(errorMessage);
    }

    /** 处理异步请求超时异常 */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Object handleAsyncRequestTimeoutException(AsyncRequestTimeoutException e, HttpServletRequest request,
            HttpServletResponse response) {
        logger.error("异步请求超时: {}", request.getRequestURL(), e);

        // 处理SSE请求的超时情况
        if (isSseRequest(request)) {
            writeSseErrorResponse(response, e);
            return null;
        }

        // 非SSE请求返回标准JSON响应
        return Result.error(503, "请求处理超时，请重试");
    }

    /** 处理客户端主动断开连接（SSE常见） */
    @ExceptionHandler(ClientAbortException.class)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handleClientAbortException(ClientAbortException e, HttpServletRequest request) {
        logger.debug("客户端已断开连接，忽略异常: URL={}, err={}", request.getRequestURL(), e.getMessage());
    }

    /** 处理未预期的异常 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Object handleException(Exception e, HttpServletRequest request, HttpServletResponse response) {
        logger.error("未预期的异常: URL={}", request.getRequestURL(), e);

        // SSE请求异常必须返回SSE结构，避免 Result JSON 与 text/event-stream 冲突
        if (isSseRequest(request)) {
            writeSseErrorResponse(response, e);
            return null;
        }

        return Result.serverError("服务器内部错误: " + e.getMessage());
    }

    /** 判断当前请求是否为SSE请求 */
    private boolean isSseRequest(HttpServletRequest request) {
        if (Boolean.TRUE.equals(request.getAttribute(SSE_REQUEST_ATTRIBUTE))) {
            return true;
        }

        if (acceptsEventStream(request)) {
            return true;
        }

        if (producesEventStream(request)) {
            return true;
        }

        // 兜底：根据命中的Controller方法返回值判断是否SSE
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof HandlerMethod method) {
            return SseEmitter.class.isAssignableFrom(method.getMethod().getReturnType());
        }

        return matchesKnownSseEndpoint(request);
    }

    private boolean acceptsEventStream(HttpServletRequest request) {
        Enumeration<String> acceptHeaders = request.getHeaders("Accept");
        while (acceptHeaders.hasMoreElements()) {
            String accept = acceptHeaders.nextElement();
            if (accept != null && accept.toLowerCase().contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
                return true;
            }
        }
        return false;
    }

    private boolean producesEventStream(HttpServletRequest request) {
        Object producible = request.getAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
        if (!(producible instanceof Set<?> mediaTypes)) {
            return false;
        }
        return mediaTypes.stream().filter(MediaType.class::isInstance).map(MediaType.class::cast)
                .anyMatch(mediaType -> mediaType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    private boolean matchesKnownSseEndpoint(HttpServletRequest request) {
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

    /** 直接写入SSE错误响应，避免异常处理器走普通消息转换链 */
    private void writeSseErrorResponse(HttpServletResponse response, Throwable throwable) {
        if (response == null || response.isCommitted()) {
            return;
        }
        try {
            response.setStatus(HttpStatus.OK.value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            String payload = JsonUtils.toJsonString(ChatErrorResponseFactory.fromThrowable(throwable));
            response.getWriter().write("event: error\n");
            response.getWriter().write("data: " + payload + "\n\n");
            response.getWriter().flush();
        } catch (IOException ioException) {
            logger.debug("发送SSE异常消息失败（可能客户端已断开）: {}", ioException.getMessage());
        }
    }
}
