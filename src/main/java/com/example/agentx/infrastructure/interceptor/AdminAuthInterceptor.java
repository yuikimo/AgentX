package com.example.agentx.infrastructure.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import com.example.agentx.domain.user.model.UserEntity;
import com.example.agentx.domain.user.service.UserDomainService;
import com.example.agentx.infrastructure.auth.UserContext;
import com.example.agentx.infrastructure.exception.BusinessException;

/**
 * 管理员权限拦截器 用于拦截需要管理员权限的请求
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final UserDomainService userDomainService;

    public AdminAuthInterceptor(UserDomainService userDomainService) {
        this.userDomainService = userDomainService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = UserContext.getCurrentUserId();
        UserEntity user = userDomainService.getUserInfo(userId);

        if (user == null || !user.isAdmin()) {
            throw new BusinessException("无权限访问管理功能");
        }

        return true;
    }
}