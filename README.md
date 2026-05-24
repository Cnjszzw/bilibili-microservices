# Bilibili 微服务改造

基于 Spring Cloud Alibaba 对 BiliBili 仿站单体应用进行微服务架构升级。GitHub: https://github.com/Cnjszzw/bilibili-microservices

---

## Phase 1：基础设施搭建（已完成）

### 整体架构

```
浏览器(frontend:7070)
    │
    │ HTTP API (携带 token Header)
    │ 图片/视频 (<img>/<video> 标签，无 token)
    │ WebSocket (绕过 Gateway，直连)
    │
    ▼
bilibili-gateway:8000 ─── Nacos:8848 ─── bilibili-legacy-service:8070
    │                     (注册中心)          (老单体应用)
    │
    ├─ AuthGlobalFilter   ← 统一 JWT 鉴权
    ├─ globalcors         ← 统一跨域
    └─ routes             ← 路由转发
```

### 1. Nacos 服务注册发现

- 现有单体应用 (`imooc-bilibili-api`) 接入 Nacos Discovery，服务名 `bilibili-legacy-service`
- Gateway 注册到 Nacos，服务名 `bilibili-gateway`
- Gateway 通过 `lb://bilibili-legacy-service` 发现下游服务，而非硬编码 IP 端口

### 2. 路由转发

```yaml
routes:
  - id: bilibili-legacy-service
    uri: lb://bilibili-legacy-service   # lb = 负载均衡，走 Nacos 服务发现
    predicates:
      - Path=/**                         # Phase 1 全量转发到一个后端
    filters:
      - StripPrefix=0
```

关键设计：
- 用 `lb://` 服务名而不是 `http://127.0.0.1:8070`，将来拆出新服务后改一行 yaml 即可切流
- Phase 1 只有一个后端，全部请求转发。后续按路径前缀分发（如 `/videos/**` → content-service，`/user/**` → user-service）

### 3. AuthGlobalFilter 统一鉴权

过滤器执行顺序（从高到低）：

```
请求进来
  ↓
  ├─ OPTIONS 预检请求?           → 放行（CORS 预检不携带 token）
  ├─ Sec-Fetch-Dest: image/video/audio? → 放行（<img>/<video> 标签无法携带自定义 Header）
  ├─ 白名单路径?                 → 放行（注册、登录等公开接口）
  └─ 校验 JWT token             → 通过：注入 X-User-Id → 失败：返回 401
```

**为什么注入 X-User-Id？**

单体架构里，每个 Controller 通过 `UserSupport` 从请求头取 token 解析 userId。微服务架构下，鉴权在 Gateway 统一做，下游服务不再关心 token。Gateway 校验 token 后把 userId 塞入 `X-User-Id` 请求头传给下游，下游直接信任即可。

```java
ServerHttpRequest mutatedRequest = request.mutate()
        .header("X-User-Id", String.valueOf(userId))
        .build();
```

**踩过的坑：**

| 坑 | 原因 | 修复 |
|----|------|------|
| OPTIONS 请求 401 → CORS error | CORS 预检请求不携带 token，AuthGlobalFilter 拦截 | 添加 OPTIONS 请求直接放行 |
| 视频/图片 401 → 无法播放 | `<img>/<video>` 标签请求无法携带自定义 Header | 添加 Sec-Fetch-Dest 判断放行 |
| CORS 响应头重复 → CORS error | Gateway 和老服务各加了一份 CORS 头 | 禁用老服务 CorsConfig，统一由 Gateway 处理 |

### 4. 跨域

- 使用 Gateway 原生 `globalcors` 配置，而非 `CorsWebFilter`（`CorsWebFilter` 是 WebFlux 的 WebFilter，与 Gateway GlobalFilter 执行顺序不同，导致代理场景下 CORS 头丢失）
- 老服务的 `CorsConfig.java` 已注释：**微服务架构下 CORS 只在 Gateway 层处理**，避免响应头重复

### 5. bilibili-common 共享模块

```
bilibili-common/               # 被依赖的 jar 包，无 main 方法，不能独立启动
├── domain/JsonResponse.java
├── exception/ConditionException.java
├── util/TokenUtil.java        # JWT 生成与校验（RSA256）
└── util/RSAUtil.java          # RSA 加解密工具
```

设计意图：后续所有服务都依赖此模块，避免每个服务复制粘贴相同的工具类。

---

## 项目结构

```
microservices/                    # IDEA 打开此目录作为项目根
├── pom.xml                       # 父 POM（Spring Boot 2.5.1 + Cloud 2020.0.6 + Alibaba 2021.1）
├── bilibili-common/              # 共享模块（jar 包）
│   ├── pom.xml
│   └── src/main/java/com/bilibili/common/
│       ├── domain/JsonResponse.java
│       ├── exception/ConditionException.java
│       └── util/{TokenUtil,RSAUtil}.java
├── bilibili-gateway/             # 网关服务（端口 8000）
│   ├── pom.xml
│   ├── src/main/java/com/bilibili/gateway/
│   │   ├── GatewayApplication.java
│   │   ├── config/CorsConfig.java
│   │   └── filter/AuthGlobalFilter.java
│   └── src/main/resources/application.yml
├── bilibili-content-service/     # 视频域（Phase 2 待建）
├── bilibili-user-service/        # 用户域（Phase 3 待建）
├── bilibili-social-service/      # 动态域（Phase 4 待建）
└── bilibili-danmu-service/       # 弹幕域（Phase 4 待建）
```

**关键理解：** `microservices/` 是根目录（父 POM），里面的子模块是平级关系，不是从属关系。`bilibili-common` 只是被依赖的 jar 包，`bilibili-gateway` 才是可启动的 Spring Boot 应用。

---

## 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 2.5.1 | 基础框架 |
| Spring Cloud | 2020.0.6 | 微服务基础设施 |
| Spring Cloud Alibaba | 2021.1 | Nacos 服务发现/配置中心 |
| Spring Cloud Gateway | - | 统一 API 网关 |
| Nacos Server | 2.3.0 (推荐) | 服务注册与配置中心 |
| Java | 8 | 编译目标版本 |

---

## 启动步骤

### 1. 启动 Nacos Server

```bash
# Docker 方式（推荐）
docker run --name nacos -e MODE=standalone -p 8848:8848 -d nacos/nacos-server:v2.3.0

# 或二进制方式
sh startup.sh -m standalone
```

控制台：http://127.0.0.1:8848/nacos （账号/密码：nacos/nacos）

### 2. 启动老单体应用

```bash
cd /Users/zhaozhiwen/CodeRepo/bilibili/server/imooc-bilibili
mvn clean install -DskipTests
cd imooc-bilibili-api
mvn spring-boot:run
```

Nacos 控制台服务列表应出现 `bilibili-legacy-service`

### 3. 启动 Gateway

```bash
cd /Users/zhaozhiwen/CodeRepo/bilibili/microservices
mvn clean install -DskipTests
cd bilibili-gateway
mvn spring-boot:run
```

Nacos 控制台服务列表应出现 `bilibili-gateway`

---

## 验证方式

| 场景 | 请求 | 预期 |
|------|------|------|
| 白名单放行 | `GET http://localhost:8000/rsa-pks` | 正常返回 RSA 公钥 |
| 无 Token → 401 | `GET http://localhost:8000/user` | 401 |
| 携带 Token → 正常 | `GET http://localhost:8000/user` Header: `token=xxx` | 正常返回 |
| 视频流 | `<video src="http://localhost:8000/video-slices?url=xxx.mp4">` | 正常播放 |
| 图片 | `<img src="http://localhost:8000/viewImage?url=xxx.png">` | 正常显示 |

---

## 简历相关内容

> 基于 Spring Cloud Alibaba 完成单体应用微服务化改造，搭建 Nacos 服务注册中心与 Spring Cloud Gateway 统一网关。通过全局过滤器实现 JWT 统一鉴权与 X-User-Id 请求头注入，实现下游服务无鉴权逻辑的简洁架构。解决 Gateway 场景下 CORS 跨域、浏览器媒体请求鉴权等一系列实际问题，采用单仓库多模块的工程管理方式。

---

---

## FAQ

### Q: X-User-Id 注入原理是什么？下游服务需要改代码吗？

**原理：信任边界前移。**

```
单体架构：
  Controller → UserSupport.getCurrentUserId() → TokenUtil.verifyToken(token) → userId
  （每个 Controller 各自从 token 解析 userId，重复代码多、密钥分散）

微服务架构：
  Gateway → TokenUtil.verifyToken(token) → 追加 X-User-Id 请求头 → 下游直接用
  （鉴权只在入口做一次，结果通过请求头传递）
```

**Phase 1 需要改老代码吗？不需要。**

Gateway 转发时原始请求头原封不动传过去（包括 `token`），老服务的 `UserSupport` 还是从 `token` 解析 userId，能正常工作。`X-User-Id` 目前只是附赠信息，老代码不用它。

**什么时候真正起作用？**

Phase 2 拆分 Content Service 时，新服务从零开始写，不需要继承老代码的 `TokenUtil` 和 `UserSupport`，直接读请求头：

```java
// 新服务写法：不需要 JWT 依赖
public void addVideo(@RequestHeader("X-User-Id") Long userId) { ... }
```

**为什么不在 Phase 1 改老代码？**

改老代码是纯风险没收益的事。等拆服务时新服务用新写法，老代码随着域被拆完自然消失，不需要主动去改。

---

### Q: Feign 远程调用涉及的@FeignClient接口、DTO 实体如何避免在两个服务中重复定义？

远程调用涉及两个层面的"重复"问题：

- **接口层面**：`@FeignClient` 接口在调用方定义，Controller 在提供方定义，两者定义的是同一套契约（路径、参数、返回值），是否各写各的？
- **实体层面**：传入和返回的 Java 对象（User、UserMoment 等），是否两个服务各定义一份？

---

#### 一、Feign vs OpenFeign

同一个东西，只是换了维护者：

| | Feign | OpenFeign |
|--|-------|-----------|
| 全称 | Netflix Feign | Spring Cloud OpenFeign |
| 维护方 | Netflix（已停维） | Spring Cloud 团队 |
| Maven坐标 | `com.netflix.feign:feign-core` | `org.springframework.cloud:spring-cloud-starter-openfeign` |
| 注解 | `@RequestLine` | `@GetMapping` / `@PostMapping`（Spring MVC 风格） |

本项目的 `spring-cloud-starter-openfeign` 就是 OpenFeign，日常交流大家习惯叫 Feign。

---

#### 二、问题1：@FeignClient 接口重复问题

**当前 Phase 2 的现状——接口确实写了两遍：**

调用方（content-service）定义 Feign 接口：

```java
// content-service: com/bilibili/content/feign/LegacyMomentFeignClient.java
@FeignClient(name = "bilibili-legacy-service")
public interface LegacyMomentFeignClient {
    @PostMapping("/user-moments")
    JsonResponse<String> addUserMoments(@RequestBody UserMoment userMoment);
}
```

提供方（legacy-service）实现对应的 Controller：

```java
// legacy-service: com/imooc/bilibili/api/UserMomentsApi.java
@RestController
public class UserMomentsApi {
    @PostMapping("/user-moments")
    public JsonResponse<String> addUserMoments(@RequestBody UserMoment userMoment) {
        userMomentsService.addUserMoments(userMoment);
        return JsonResponse.success();
    }
}
```

**问题**：路径 `/user-moments`、方法签名、参数类型在两处各自定义了一遍。如果提供方改了接口路径，调用方不知道，运行时直接 404。

**解决方案——接口提到 common，提供方定义契约，调用方只依赖接口：**

Phase 3 的改进方向：

```java
// bilibili-common/src/main/java/com/bilibili/common/api/MomentFeignApi.java
// 这一步只需要定义一次，放 common 里
@FeignClient(name = "bilibili-social-service")  // Phase 4 会独立为 social-service
public interface MomentFeignApi {
    @PostMapping("/user-moments")
    JsonResponse<String> addUserMoments(@RequestBody UserMomentDTO dto);
}
```

然后两方各自依赖 common，不再各自定义：

```
调用方 (content-service) → implements MomentFeignApi  →  只需要 @Autowired MomentFeignApi 即可
                                                                      ↑
提供方 (social-service)   → 提供 /user-moments 的 Controller  ←  提供方内部实现
```

- **接口是提供方定义的契约**：提供方承诺"我提供 `/user-moments`，参数是 `UserMomentDTO`"。
- `@FeignClient` 接口只需在 common 中定义一次，调用方不需要知道提供方内部怎么实现的。
- 提供方可以换成任何实现（直接写库、发 MQ、调第三方），调用方不关心。

**但有一个讲究**：`@FeignClient` 接口应该由谁放到 common？

由**提供方**定义并放到 common。因为提供方最清楚自己暴露了什么能力、接口会怎么演进。调用方只是消费者，不应该替提供方决定接口长什么样。

---

#### 三、问题2：实体（DTO）重复问题

Feign 调用的入参和返回值，跨服务时传什么 Java 对象？

**三种方案对比：**

| 方案 | 做法 | 优点 | 缺点 |
|------|------|------|------|
| A: 复制全量 domain 类 | 调用方把提供方的 `User.java` 完整复制一份 | Phase 2 最快跑通 | 类重复 + 调用方能看到 password/phone 等不应暴露的字段 |
| B: 共享全量 domain 类 | 把 `User.java` 提到 common，两边依赖同一个类 | 类不重复 | 所有服务依赖全量字段，改一个字段影响所有服务 |
| C: 提供方定义精简 DTO 放到 common | common 里放精简的 `UserDTO`（只有 3 个字段） | 接口清晰、字段最小化、服务解耦 | 提供方需要多做一层 domain→DTO 转换 |

**方案 C 是业界推荐做法——DTO（Data Transfer Object）：**

```java
// bilibili-common/api/dto/UserDTO.java —— 只定义一次
public class UserDTO {
    private Long id;
    private String nick;      // content-service 只需要展示 UP 主昵称
    private String avatar;    // 和头像，不需要 password、phone、salt
}

// bilibili-common/api/UserFeignApi.java —— 只定义一次
@FeignClient(name = "bilibili-user-service")
public interface UserFeignApi {
    @GetMapping("/user/info")
    UserDTO getUserInfo(@RequestParam Long userId);
}
```

```
content-service ─依赖→ common（接口+DTO） ←依赖─ user-service（提供方，内部维护完整 User domain）
                        ↑
               UserFeignApi + UserDTO
               各写一次，都在 common
```

- **DTO 各管各的**：每个 Feign 接口有自己的精简 DTO。用户接口的 DTO 是 `UserDTO`（3 个字段），动态接口的 DTO 是 `MomentDTO`（不同字段）。两者互不影响。
- **提供方负责 domain→DTO 转换**：user-service 内部从完整的 `User`（几十个字段）提取出 `UserDTO`（3 个字段）返回。
- **调用方永远看不到不应看的字段**：content-service 只需要昵称和头像，拿不到密码。

---

#### 四、当前 Phase 2 的实际情况

Phase 2 采用的是**混合策略**，不是纯方案 A，而是根据调用类型做了区分：

**1. 实体层面——复制了 domain 类（方案 A）**

content-service 的 `src/main/java/com/imooc/bilibili/domain/` 下有 26 个 domain 类，全部从 legacy 完整复制。原因：

- 共享同一个 MySQL 数据库，表结构完全相同，domain 字段暂时不会分化
- 先让编译通过、服务跑起来，验证整个链路

**2. 接口层面——@FeignClient 接口写在了调用方（方案 A 过渡态）**

`LegacyMomentFeignClient` 和 `LegacyUserFeignClient` 定义在 content-service，与 legacy 的 Controller 各自写了一遍契约：

```
content-service                           legacy-service
  LegacyMomentFeignClient                   UserMomentsApi
    @PostMapping("/user-moments")       ←→   @PostMapping("/user-moments")
    方法签名在这里定义了一次                   方法签名在这里又写了一遍
```

原因：先验证 Feign 能通过 Nacos 发现服务并成功调用，后续再用 common 统一管理接口定义。

**3. 调用策略——区分了本地调用和远程调用**

| 调用目标 | 当前方式 | 原因 |
|---------|---------|------|
| 查用户信息（`UserService.getUserInfo`） | **本地调用**（shared DB 直读） | 高频读操作，走 Feign 增加延迟；DB 暂时共享 |
| 创建动态（`UserMomentsService.addUserMoments`） | **Feign 远程调用** | 动态是 Social 域的核心写操作，content-service 不应直接写动态表 |

**Phase 3 要改进什么：**

1. 拆出 `bilibili-user-service`，用户表操作完全归属它
2. content-service 停掉本地 `UserService`，改为通过 Feign 调用 user-service
3. 将 `@FeignClient` 接口和 DTO 提取到 `bilibili-common`，消除两份重复定义
4. content-service 不再直接持有 UserDao、不再直连用户表

---

### Q: 为什么 Feign 调用经常报 404？拆服务的隐藏成本是什么？

**核心认知：微服务拆分 = 把 JVM 内部方法调用变成 HTTP 接口。**

```
单体架构：
  VideoService → userService.getUserInfo(userId) → UserDao → DB
  一个 import + 一个方法调用，搞定

微服务架构：
  VideoService → @FeignClient 调 /user/info → 提供方需要 @RestController 实现 /user/info
  两个 HTTP 接口 + 一份契约匹配，才算拆完
```

**`getInfo` 报 404 的根因**：`UserService.getUserInfo()` 原来是 JVM 内方法调用，legacy 里根本没有对应的 HTTP 端点。`@FeignClient` 不是魔法——它背后是 HTTP 调用，目标服务必须有一个真实的 Controller 来处理这个请求。

**拆分成本对照表**（以 `VideoService` 的 6 个依赖为例）：

| 原来（单体，方法调用） | 拆分后需要 | 当前 Phase 2 状态 |
|-----------------------|----------|-----------------|
| `userMomentsService.addUserMoments()` | legacy 刚好已有 `POST /user-moments` | Feign 调用成功 |
| `userService.getUserInfo()` | **需要新写** `/user-info` 端点 | 暂用本地调用（shared DB） |
| `userService.getUserInfoByUserIds()` | **需要新写** 端点 | 暂用本地调用 |
| `userCoinService.getUserCoinAmount()` | **需要新写** 端点 | 暂保留在 content-service 内 |
| `contentService.addContent()` | Content 域，随 Content Service 一起迁移了 | 本地调用 |
| `videoDao.*` | Video 域，随 Content Service 一起迁移了 | 本地调用 |

**总结**：拆分一个服务，不只是把代码搬过去。每个原来通过 `@Autowired` 注入的外部依赖，如果该依赖留在了其他服务里，就得：

1. 提供方（legacy）新写一个 HTTP 端点（Controller 方法）
2. 调用方（content-service）新写一个 `@FeignClient` 接口
3. 两边的路径、参数、返回值必须完全匹配

Phase 2 只完成了一处 Feign 调用（`addUserMoments`），恰好是因为老项目碰巧有这个 HTTP 接口。剩下的外部依赖要么留在本地（共享 DB），要么等对应的服务拆出来后按需补端点。

---

### Q: 视频投稿→发动态的分布式事务方案是如何选型的？

**场景分析：**

`addVideos()` 涉及三个存储系统（MySQL + RocketMQ + Redis）：

```
① 写视频表 t_video (MySQL)
② 写内容表 t_content (MySQL)
③ 创建动态   t_user_moment (MySQL，legacy 服务)
   └─ 发 RocketMQ 扩散消息
      └─ 写 Redis 粉丝收件箱
```

**四种方案逐一评估：**

| 方案 | 能否用 | 排除原因 |
|------|--------|---------|
| **XA** | 排除 | 强一致性导致锁持有时间长，可用性低。同样只能覆盖 DB |
| **AT (Seata)** | 排除 | UNDO_LOG 只对 MySQL 生效。MQ 已发出、Redis 已写入的情况下回滚，出现 DB 回滚但消息未回滚的二次不一致 |
| **TCC** | 排除 | 手写 try/confirm/cancel，多场景累积代码量爆炸，需处理空回滚、幂等、悬挂 |
| **最大努力通知** | ✅ 选定 | 接受短暂不一致（视频发布后动态晚1-2秒出现），用消息可靠性保证最终一致 |

**最大努力通知的三种实现层次：**

| 实现方式 | 可靠性 | 复杂度 | 结论 |
|---------|--------|--------|------|
| afterCommit 回调 | 低（JVM 崩溃窗口） | 最低 | 不够可靠 |
| **RocketMQ 事务消息** | **高（半消息+Broker 回查兜底）** | **中** | **✅ 选定** |
| 本地消息表+定时补偿 | 最高（DB 兜底） | 高 | 过度设计 |

**最终实现：RocketMQ 事务消息**

```
content-service:
  @Transactional
  addVideos() {
    videoDao.addVideos(video);         // ① 写视频
    contentService.addContent(content); // ② 写内容
    rocketMQTemplate.sendMessageInTransaction(
      "Topic-Moments", momentMsg         // ③ 半消息 → Broker
    );
  }
  // Spring TX 提交后 → Broker 回查 checkLocalTransaction → 查 Content 表验证

legacy-service (新增 MomentPersistConsumer):
  ↓ 收到 Topic-Moments 消息（与现有 MomentsGroup 消费者并行）
  userMomentsDao.addUserMoments(moment); // ④ 写动态表
  // 原有消费者继续：查粉丝 → Redis 推送
```

#### 最终实现：RocketMQ 事务消息（原生 API）

Content Service 采用和 Legacy 一致的 `rocketmq-client` 原生 API，不引入 `rocketmq-spring-boot-starter`，避免 IDEA 依赖解析问题。

**架构全景（正常链路）：**

```
POST /videos (携带 token)
  │
  ▼
Gateway:8000
  ├─ AuthGlobalFilter: token → X-User-Id: 45
  ├─ 路由 /videos → bilibili-content-service(8002)
  │
  ▼
ContentService:8002 (@Transactional)
  VideoApi.addVideos()
    └─ VideoService.addVideos()
         ├─ ① videoDao.addVideos(video)              → INSERT t_video (id=117)
         ├─ ② videoDao.batchAddVideoTags(tagList)     → INSERT t_video_tag
         ├─ ③ contentService.addContent(content)      → INSERT t_content (id=86)
         ├─ ④ 构建 UserMoment(type=0, contentId=86, userId=45)
         └─ ⑤ momentTransactionProducer.sendMessageInTransaction(msg, null)
              │
              ▼ 半消息到达 Broker，落盘（消费者不可见）
              │
         Broker 回调 MomentTransactionListener.executeLocalTransaction()
              → return UNKNOW（Spring TX 未提交，交给回查）
              │
         @Transactional 提交成功
              │
         Broker 定时回查 MomentTransactionListener.checkLocalTransaction()
              → contentDao.getContentById(86) != null
              → return COMMIT_MESSAGE
              │
              ▼ 消息对消费者可见
              │
  ┌───────────┴───────────────────────────┐
  ▼                                       ▼
Legacy: MomentsPersistGroup (新消费者)     Legacy: MomentsGroup (原有消费者)
  userMomentsDao.addUserMoments()          查询粉丝列表
  → INSERT t_user_moment ✓                 ├─ <10w: 遍历写 Redis subscribed-{fanId}
                                           └─ ≥10w: 写 Redis outbox-{userId}
                                           
ContentService (@Transactional 外)
  ElasticSearchService.addVideo(video)
  → ES 索引视频（全文搜索）
```

**涉及的工程变更：**

| 服务 | 文件 | 变更 |
|------|------|------|
| content-service | `pom.xml` | 不新增依赖，复用已有 `rocketmq-client` 4.9.1 |
| content-service | `RocketMQConfig.java` | **新增** — 创建 `TransactionMQProducer` Bean，配置回查线程池和 `MomentTransactionListener` |
| content-service | `MomentTransactionListener.java` | **新增** — 实现原生 `TransactionListener`，`executeLocalTransaction` 返回 UNKNOW，`checkLocalTransaction` 查 Content 表验证 |
| content-service | `VideoService.java` | `@Autowired TransactionMQProducer`，`sendMessageInTransaction(msg, null)` 替换 Feign 调用 |
| content-service | `ContentDao.java` + `content.xml` | 新增 `getContentById`，供事务回查使用 |
| content-service | `ContentServiceApplication.java` | 新增 `@EnableElasticsearchRepositories` |
| content-service | `LegacyMomentFeignClient.java` | **删除** |
| content-service | `LegacyUserFeignClient.java` | **删除** |
| content-service | `ContentServiceApplication.java` | 移除 `@EnableFeignClients` |
| legacy | `RocketMQConfig.java` | 新增 `MomentPersistConsumer` Bean（consumer group `MomentsPersistGroup`），消费 `Topic-Moments` 写动态表 |

**异常场景分析：**

| 异常 | 表现 | 结果 |
|------|------|------|
| Content Service 本地事务回滚 | 视频入库失败，`@Transactional` 回滚 | 半消息已到 Broker，回查时 `getContentById` 返回 null → ROLLBACK → 消息删除，消费者永远看不到 |
| Broker 回查前 Content Service 宕机 | Broker 收不到回查响应 | Broker 递增间隔重试 15 次（约 1 小时内），恢复后回查成功 → COMMIT |
| Legacy 宕机（消费者不可用） | 消息在 Broker 磁盘上积压 | 视频正常发布。Legacy 恢复后拉取积压消息继续消费，动态延迟补上 |
| MomentPersistConsumer 写 DB 失败 | DB 抛异常，不返回 CONSUME_SUCCESS | Broker 重投 16 次（10s→30s→1min→...→2h），全部失败后进死信队列，人工重放 |
| MomentsGroup 扩散消费者写 Redis 失败 | Redis 抛异常 | 同上，独立重试不影响入库消费者。可能出现「动态入库但未推送」的中间态，最终一致 |
| Broker 宕机 | 消息不可投递 | 支持主从同步/异步刷盘，同步刷盘模式消息不丢。恢复后继续投递 |

**与最初 Feign 方案对比：**

| | Feign 同步调用（已废弃） | RocketMQ 事务消息（当前） |
|--|------------------------|-------------------------|
| content→legacy 通信 | HTTP 同步 | MQ 异步 |
| Legacy 宕机 | **视频投稿失败** | 视频正常发布，动态等 Legacy 恢复后补上 |
| 一致性 | @Transactional 本地事务 | 半消息 + Broker 回查 + 消费重试，最终一致 |
| 额外依赖 | OpenFeign | 无（复用已有 rocketmq-client） |
| 延迟 | 同步等待 Legacy 返回 | 消息投递通常 < 100ms，动态基本实时出现 |

**测试验证：**

```bash
# 投稿成功（2024-05-24 已通过）
POST /videos
  → HTTP 200，t_video 新增 id=117
  → t_content 新增 id=86
  → RocketMQ 事务消息 COMMIT

# 动态可见
GET /moments?size=5&no=1
  → 5 条动态列表，包含刚才投稿的 "RocketMQ 事务测试"
```

**面试话术（为什么不用 Seata）：**

"Seata AT 的 UNDO_LOG 只能回滚数据库操作。视频投稿后的动态创建不仅写 DB，还要发 MQ 推送粉丝、写 Redis 收件箱——后两步 Seata 管不了。DB 回滚而 MQ 消息已发出，会出现二次不一致。

所以选择**分而治之**：核心写链路走本地事务保证 ACID，跨服务扩散链路用 RocketMQ 事务消息 + 消费重试实现最终一致。分布式环境没有银弹，在合适的边界处用不同的工具。"

---

### Q: Seata AT 在实际项目中有什么场景可以用？

`addVideos()` 中新增了 Seata 演示场景——**视频-标签关联通过 Feign 远程创建**：

```java
@GlobalTransactional(name = "addVideos")     // Seata：DB 操作强一致
@Transactional
public void addVideos(Video video) {
    videoDao.addVideos(video);                       // ① 本地 DB
    legacyTagFeignClient.batchAddVideoTags(tagList);  // ② Feign→Legacy DB（Seata 管理）
    contentService.addContent(content);              // ③ 本地 DB
    momentTransactionProducer.sendMessageInTransaction(...); // ④ MQ 异步（不在 Seata 范围）
}
```

**同一个方法里两种方案共存，形成对比：**

| 子操作 | 方案 | 原因 |
|--------|------|------|
| ①②③ 视频+标签+内容 | Seata AT（@GlobalTransactional） | 纯 DB 操作，UNDO_LOG 即可回滚 |
| ④ 发动态 | RocketMQ 事务消息 | 下游涉及 MQ + Redis，Seata 管不了 |

**面试话术：**

"`addVideos` 是我刻意设计的对比展示——标签关联走 Seata 强一致，动态创建走 RocketMQ 异步解耦。我想表达的观点是：**不同的子操作选不同的事务方案，按需组合。** 没有一种方案能通吃所有场景。"

**Seata 部署（待执行）：**

```bash
# 1. Docker 启动 Seata Server
docker run --name seata -p 8091:8091 -p 7091:7091 -d seataio/seata-server:1.6.1

# 2. MySQL 创建 UNDO_LOG 表
mysql -u root -p imooc-bilibili < seata-setup.sql
```

**回滚验证（部署 Seata Server 后）：**

停掉 legacy → POST /videos → Feign 调用失败 → @GlobalTransactional 回滚 → t_video 和 t_video_tag 都没有新记录。

---

### Q: 事务消息能保证消费者一定成功吗？

**不能。这是对事务消息最常见的误解。**

事务消息保证的是 **Producer 端**（发消息的一方）的原子性：

```
视频写成功 ←→ 消息一定会被 COMMIT，消费者可见
视频写失败 ←→ 消息一定会被 ROLLBACK，消费者永远看不到
```

**它不保证 Consumer 端**（消费消息的一方）。消费者的可靠性靠另一套机制——消费重试：

```
消费者收到消息
  ↓
执行业务逻辑（写库、写 Redis...）
  ↓
成功 → return CONSUME_SUCCESS → Broker 标记消费完成
失败 → 抛异常不 ACK → Broker 重新投递
  ↓
重试 16 次（间隔递增：10s → 30s → 1min → ... → 2h）
  ↓
16 次全失败 → 死信队列 → 人工介入
```

**两个消费者各自独立，互不影响：**

```
                    Topic-Moments（一条消息）
                   ↙                     ↘
      MomentPersistGroup              MomentsGroup
      (入库消费者)                      (扩散消费者)
          ↓                                ↓
    写 DB 可能失败                    写 Redis 可能失败
          ↓                                ↓
    独立重试 16 次                    独立重试 16 次
          ↓                                ↓
    最终成功 or 死信                  最终成功 or 死信
```

一个成功不等于另一个也成功。可能出现「动态入库了但粉丝收件箱还没更新」的中间态——扩散重试成功后恢复一致。这就是最终一致性的含义。

**三层保证对照：**

| 层 | 机制 | 保证什么 | 不保证什么 |
|----|------|---------|-----------|
| Producer 事务消息 | 半消息 + Broker 回查 | 本地事务和消息发送原子性 | 消费者是否成功 |
| Consumer 消费重试 | 不 ACK → 重投 16 次 | 消费至少被尝试 16 次 | 16 次内一定成功 |
| 死信队列 | 16 次失败后转人工 | 消息不丢、可追溯 | 自动恢复 |

没有一层能单独保证端到端一致，三层合起来才构成最终一致性。

---

## 下一步（Phase 3）

拆分 User Service：
1. 新建 `bilibili-user-service`，迁移用户相关 Domain/DAO/Service/API
2. Content Service 停掉本地 `UserService`，改为 Feign 调用 user-service
3. 将 `@FeignClient` 接口和 DTO 提取到 `bilibili-common`，消除重复定义
4. Content Service 不再直接持有 UserDao，用户表完全归属 user-service
5. Sentinel 接入：Gateway 层限流 + Feign 层熔断降级
