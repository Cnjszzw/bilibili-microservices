package com.imooc.bilibili.api.support;

import com.imooc.bilibili.domain.exception.ConditionException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * 微服务架构下的 UserSupport
 * Gateway 已完成 JWT 校验并将 userId 注入 X-User-Id 请求头
 * 下游服务直接信任该请求头，无需重复解析 token
 */
@Component
public class UserSupport {

    public Long getCurrentUserId() {
        ServletRequestAttributes requestAttributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = requestAttributes.getRequest();
        String userIdStr = request.getHeader("X-User-Id");
        if (userIdStr == null || userIdStr.isEmpty()) {
            throw new ConditionException("非法用户");
        }
        Long userId = Long.valueOf(userIdStr);
        if (userId < 0) {
            throw new ConditionException("非法用户");
        }
        return userId;
    }
}
