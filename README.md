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

## 下一步（Phase 2）

拆分 Content Service：
1. 新建 `bilibili-content-service`，迁移视频相关 Domain
2. 通过 Feign 调用 User Service（或当前单体中的用户接口）
3. 引入 Seata 分布式事务（视频投稿 → 自动发动态）
