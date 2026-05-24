package com.bilibili.content.feign;

import com.imooc.bilibili.domain.JsonResponse;
import com.imooc.bilibili.domain.User;
import com.imooc.bilibili.domain.UserInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

/**
 * Feign 调用 Legacy 用户服务
 * Legacy 侧对应端点：UserApi.getUserInfo() / UserApi.getUserInfoByUserIds()
 */
@FeignClient(name = "bilibili-legacy-service", contextId = "legacyUser")
public interface LegacyUserFeignClient {

    @GetMapping("/user/info")
    JsonResponse<User> getUserInfo(@RequestParam("userId") Long userId);

    @GetMapping("/user/infos")
    JsonResponse<List<UserInfo>> getUserInfoByUserIds(@RequestParam("userIds") Set<Long> userIds);
}
