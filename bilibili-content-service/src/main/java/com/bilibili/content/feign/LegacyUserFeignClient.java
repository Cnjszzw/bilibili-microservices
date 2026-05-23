package com.bilibili.content.feign;

import com.imooc.bilibili.domain.User;
import com.imooc.bilibili.domain.UserInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

/**
 * 通过 Feign 调用 legacy 用户服务获取用户信息
 */
@FeignClient(name = "bilibili-legacy-service")
public interface LegacyUserFeignClient {

    @GetMapping("/user/info")
    User getUserInfo(@RequestParam("userId") Long userId);

    @GetMapping("/user/infos")
    List<UserInfo> getUserInfoByUserIds(@RequestParam("userIds") Set<Long> userIds);
}
