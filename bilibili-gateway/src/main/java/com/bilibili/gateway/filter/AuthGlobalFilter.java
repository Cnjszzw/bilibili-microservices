package com.bilibili.gateway.filter;

import com.bilibili.common.util.TokenUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Gateway 全局鉴权过滤器
 * 1. 白名单路径直接放行
 * 2. 非白名单路径校验 JWT Token
 * 3. 校验通过后注入 X-User-Id 请求头，下游服务直接信任该头部
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    // 白名单路径（不需要 token）
    // 包含：注册登录、游客可访问的视频浏览接口等
    private static final String[] WHITE_LIST = {
            "/rsa-pks",
            "/users",
            "/user-tokens",
            "/user-tokens/**",
            "/demo/**",
            // 游客可访问的视频接口（不调 UserSupport 或内部 try-catch 兼容游客）
            "/videos",
            "/video-details",
            "/video-slices",
            "/video-slices-simple",
            "/viewImage",
            "/video-comments",
            "/video-likes",
            "/video-collections",
            "/video-coins",
            "/video-view-counts",
            "/video-triple-clicks",
            "/danmus",
            "/moments",
            "/contents",
    };

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 放行 CORS 预检请求（OPTIONS）
        if (request.getMethodValue().equalsIgnoreCase("OPTIONS")) {
            return chain.filter(exchange);
        }

        // 放行浏览器媒体元素请求（<img>/<video>/<audio>），无法携带自定义 Header
        String secFetchDest = request.getHeaders().getFirst("Sec-Fetch-Dest");
        if ("image".equalsIgnoreCase(secFetchDest)
                || "video".equalsIgnoreCase(secFetchDest)
                || "audio".equalsIgnoreCase(secFetchDest)) {
            return chain.filter(exchange);
        }

        // 白名单放行
        if (isWhiteList(path)) {
            return chain.filter(exchange);
        }

        // 获取 token
        String token = request.getHeaders().getFirst("token");
        if (token == null || token.isEmpty()) {
            return unauthorized(exchange.getResponse(), "缺少 token");
        }

        // 校验 token
        Long userId;
        try {
            userId = TokenUtil.verifyToken(token);
        } catch (Exception e) {
            return unauthorized(exchange.getResponse(), e.getMessage());
        }

        // 注入 X-User-Id 请求头，传递给下游服务
        ServerHttpRequest mutatedRequest = request.mutate()
                .header("X-User-Id", String.valueOf(userId))
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private boolean isWhiteList(String path) {
        for (String pattern : WHITE_LIST) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> unauthorized(ServerHttpResponse response, String msg) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");

        Map<String, Object> result = new HashMap<>();
        result.put("code", "401");
        result.put("msg", msg);

        ObjectMapper mapper = new ObjectMapper();
        String json;
        try {
            json = mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            json = "{\"code\":\"401\",\"msg\":\"Unauthorized\"}";
        }

        DataBuffer buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 优先级较高，确保在路由之前执行
        return -100;
    }
}
