# Bilibili 微服务改造 - Phase 1

## 已完成内容

### 1. Nacos 服务注册中心接入
- 现有单体应用 (`imooc-bilibili-api`) 已接入 Nacos Discovery
- 服务名：`bilibili-legacy-service`
- 新增 `@EnableDiscoveryClient` 注解

### 2. Spring Cloud Gateway 统一网关
- 新建 `bilibili-gateway` 模块，端口 **8000**
- 所有请求通过 Gateway 路由到 `bilibili-legacy-service`
- **AuthGlobalFilter** 实现统一 JWT 鉴权：
  - 白名单路径直接放行（`/rsa-pks`、`/users`、`/user-tokens`、`/demo/**`）
  - 其他路径校验 `token` Header
  - 校验通过后注入 `X-User-Id` 请求头传递给下游

### 3. bilibili-common 共享模块
- 抽取 `JsonResponse`、`ConditionException`、`TokenUtil`、`RSAUtil`
- 为后续服务拆分提供共享基础类

## 项目结构

```
microservices/
├── pom.xml                           # 父 POM（Spring Cloud Alibaba BOM）
├── bilibili-common/                  # 共享模块
│   ├── pom.xml
│   └── src/main/java/com/bilibili/common/
│       ├── domain/JsonResponse.java
│       ├── exception/ConditionException.java
│       └── util/TokenUtil.java
│       └── util/RSAUtil.java
└── bilibili-gateway/                 # 统一网关
    ├── pom.xml
    └── src/main/java/com/bilibili/gateway/
        ├── GatewayApplication.java
        └── filter/AuthGlobalFilter.java
    └── src/main/resources/application.yml
```

## 启动步骤

### 1. 启动 Nacos Server

```bash
# 下载并启动 Nacos（standalone 模式）
# https://nacos.io/zh-cn/docs/quick-start.html
sh startup.sh -m standalone
# Windows: startup.cmd -m standalone
```

默认地址：http://127.0.0.1:8848/nacos （账号/密码：nacos/nacos）

### 2. 启动现有单体应用

```bash
cd /Users/zhaozhiwen/CodeRepo/bilibili/server/imooc-bilibili
mvn clean install -DskipTests
cd imooc-bilibili-api
mvn spring-boot:run
```

确认服务注册到 Nacos：在 Nacos 控制台的服务列表中应看到 `bilibili-legacy-service`

### 3. 启动 Gateway

```bash
cd /Users/zhaozhiwen/CodeRepo/bilibili/microservices
mvn clean install -DskipTests
cd bilibili-gateway
mvn spring-boot:run
```

确认 Gateway 注册到 Nacos：服务列表中应看到 `bilibili-gateway`

## 验证方式

| 场景 | 请求 | 预期结果 |
|------|------|---------|
| 白名单放行 | `GET http://localhost:8000/rsa-pks` | 正常返回 RSA 公钥 |
| 无 Token 访问受保护接口 | `GET http://localhost:8000/user` | 401 Unauthorized |
| 携带 Token 访问 | `GET http://localhost:8000/user` Header: `token=xxx` | 正常返回，下游收到 `X-User-Id` |
| 直接访问后端 | `GET http://localhost:8070/user` Header: `token=xxx` | 正常返回（兼容原有调用方式）|

## 简历写法

> 基于 Spring Cloud Alibaba 搭建微服务基础设施，引入 Nacos 实现服务注册发现，构建 Spring Cloud Gateway 统一网关承担路由转发与 JWT 统一鉴权职责，通过全局过滤器注入用户标识请求头，实现下游服务无鉴权逻辑的简洁架构。

## 下一步（Phase 2）

拆分 Content Service：
1. 新建 `bilibili-content-service`，迁移视频相关 Domain
2. 通过 Feign 调用 User Service（或当前单体中的用户接口）
3. 引入 Seata 分布式事务（视频投稿 → 自动发动态）
