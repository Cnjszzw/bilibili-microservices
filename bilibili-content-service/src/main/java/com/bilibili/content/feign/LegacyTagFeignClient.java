package com.bilibili.content.feign;

import com.imooc.bilibili.domain.JsonResponse;
import com.imooc.bilibili.domain.VideoTag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * Feign 调用 Legacy 标签服务，创建视频-标签关联
 * Seata @GlobalTransactional 保证与视频入库的原子性
 */
@FeignClient(name = "bilibili-legacy-service", contextId = "legacyTag")
public interface LegacyTagFeignClient {

    @PostMapping(value = "/video-tags", consumes = "application/json")
    JsonResponse<String> batchAddVideoTags(@RequestBody List<VideoTag> tagList);
}
