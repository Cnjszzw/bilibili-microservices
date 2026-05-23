package com.bilibili.content.feign;

import com.imooc.bilibili.domain.UserMoment;
import com.imooc.bilibili.domain.JsonResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 通过 Feign 调用 legacy 动态服务创建动态
 * 对应老代码中 UserMomentsService.addUserMoments()
 */
@FeignClient(name = "bilibili-legacy-service", contextId = "legacyMoment")
public interface LegacyMomentFeignClient {

    @PostMapping("/user-moments")
    JsonResponse<String> addUserMoments(@RequestBody UserMoment userMoment);
}
