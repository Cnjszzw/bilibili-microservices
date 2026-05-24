package com.bilibili.content.feign;

import com.imooc.bilibili.domain.User;
import com.imooc.bilibili.domain.UserInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

/**
 * Feign 调用 Legacy 用户服务
 * 返回直接类型而非泛型 JsonResponse，避免 Jackson 反序列化时泛型丢失
 */
@FeignClient(name = "bilibili-legacy-service", contextId = "legacyUser")
public interface LegacyUserFeignClient {

    @GetMapping("/user/info")
    User getUserInfo(@RequestParam("userId") Long userId);

    @GetMapping("/user/infos")
    List<UserInfo> getUserInfoByUserIds(@RequestParam("userIds") Set<Long> userIds);
}
