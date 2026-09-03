# 聊天室系统后端（B0-B5）+ probe-client 实施计划

## TL;DR

> **Quick Summary**: 基于《聊天室系统-后端OpenCode实施文档.md》在现有 Spring Boot 4.1.1 脚手架上完整实现后端 B0-B5 全部六个阶段（工程/数据库 → 轻量登录 → WebSocket 聊天 → 文件分块服务 → TCP/UDP 网络实验 → 性能对比/WebRTC/管理），并新建独立 probe-client 网络探针项目。按 6 个执行波次 + 终审波次推进，每任务实现与 JUnit 测试同步交付。
>
> **Deliverables**:
> - 可启动的 chatBackend 应用（Java 21 / Spring Boot 4.1.1 / MyBatis-Plus / MySQL / Flyway / Netty）
> - 6 张表的 Flyway 迁移脚本 + admin 种子账号
> - 注册/登录/JWT/拦截器、WebSocket 聊天全协议、文件分块上传/差错重传/Range 下载
> - TCP/UDP Echo 服务（9001/9002）+ 延时/带宽测试 API + CSV 输出
> - Blocking(9101)/NIO(9102) 对比服务 + 压测客户端
> - WebRTC 信令中转 + ADMIN 管理接口
> - 独立 probe-client 项目（D:\learning\chat_room\probe-client）
> - 全套 JUnit 测试（文档第 16 章清单全覆盖）
>
> **Estimated Effort**: XL（约 34 个实现任务 + 4 个终审任务）
> **Parallel Execution**: YES - 6 个执行波次 + 1 个终审波次
> **Critical Path**: T1 pom → T5 MyBatis/测试基建 → T7/T8 用户/JWT → T10 拦截器 → T16 WS Handler → T21 分块上传 → T22 合并 → T27 延时测试 → T30 对比服务 → T31 压测 → F1-F4

---

## Context

### Original Request
用户要求基于 `doc/聊天室系统-后端OpenCode实施文档.md`（813 行完整规格）构建详细实施计划。文档固定技术栈、阶段划分（B0-B5）、验收标准、测试清单（第 16 章）。

### Interview Summary
**Key Discussions**:
- Java 版本：**采用 JDK 21**（本机仅有 JDK 21，文档要求 25；Spring Boot 4.1 支持 Java 17-26，21 完全兼容且不依赖 preview feature）
- 包名：**保留 `fun.hatsumi.chatbackend`**（文档硬编码 `com.hlx.chatroom`，所有 @MapperScan/type-aliases/目录引用按实际包名适配）
- MySQL：**用户自行手动创建** `chatroom_lab` 库 + `chatroom/chatroom123` 用户（前置条件），计划只做连接验证
- probe-client：**纳入计划**，位于 `D:\learning\chat_room\probe-client`，独立 Java/Maven 项目，纯 Netty/Socket 无 Spring
- 范围：**单一计划覆盖 B0-B5 全部**；测试策略：**JUnit 5 + Spring Boot Test，实现+测试同步交付**

**Research Findings** (librarian 验证):
- ⚠️ **文档 pom.xml 有误**：Boot 4 中 Flyway 自动配置拆分到独立模块，必须用 `spring-boot-starter-flyway`（裸 `flyway-core` 不生效）+ `flyway-mysql`（否则报 "Unsupported Database: MySQL 8.0"）→ 本计划修正
- ⚠️ **文档 starter 名称过时**：Boot 4 中 `spring-boot-starter-web` 已更名 `spring-boot-starter-webmvc`（现有脚手架已是正确名称）；`@WebMvcTest` 切片在 `spring-boot-webmvc-test`
- ✅ `com.baomidou:mybatis-plus-spring-boot4-starter:3.5.17` Maven Central 存在，兼容 Boot 4.1.1（parent BOM 覆盖版本）；分页拦截器用 `MybatisPlusInterceptor` Bean
- ✅ WebSocketConfigurer + TextWebSocketHandler + registerWebSocketHandlers 在 Boot 4 完全有效
- ✅ java-jwt 4.6.0 非 BOM 管理须显式版本；HMAC256 密钥须 ≥32 字节；`acceptLeeway` 单位秒；其 Jackson2 运行时依赖与 Boot 4 的 Jackson3 共存无冲突
- ⚠️ 现有 pom 的 `spring-boot-starter-webflux`、`batch-jdbc` 及对应 test starter 为多余依赖（servlet 栈应用不需要），须删除
- 前端契约（room-chat）：`http://localhost:8080/api` + `ws://localhost:8080/ws/chat`，Token 存 localStorage，2MiB 分块 + Web Crypto SHA-256 + 并发 3 + 单块重试 5 次退避——后端 422/幂等/401 语义必须稳定

### Gap Analysis (自审，代替 Metis)
**识别并解决的缺口**:
1. **CORS**：文档未提，但前端 Vite dev（localhost:5173）直连 8080 需要 CORS + OPTIONS 预检豁免 → T2 配置，T10 拦截器排除 OPTIONS
2. **ADMIN 账号引导**：文档未定义首个 ADMIN 来源 → V2 种子迁移插入 `admin` 账号（BCrypt 预计算哈希，开发密码 `admin123456`，文档注明）
3. **测试数据库隔离**：@SpringBootTest 会污染开发库 → 测试用 `chatroom_lab_test`（URL 带 `createDatabaseIfNotExist=true`，需授权；见前置条件）
4. **心跳/超时可配置**：20s/60s/5s 硬编码会拖慢测试 → 全部走 `chatroom.*` 配置并在测试 profile 缩短
5. **差错模拟种子**：文档要求固定种子可自动化 → `chatroom.error-simulation.seed` 配置项（null=随机）
6. **管理端 error-rate 运行时修改**：不能只靠 application.yml → RuntimeSettingsService 内存态 + 双向同步
7. **WebSocket 消息长度限制**：文档 17 章要求 → 注册时用 ConcurrentWebSocketSessionDecorator 限制缓冲与发送超时

---

## Work Objectives

### Core Objective
在现有脚手架上按文档 B0-B5 顺序交付完整后端 + probe-client，每个功能任务内含对应 JUnit 测试，最终 `mvnw.cmd clean package` 全绿。

### Concrete Deliverables
- `pom.xml`（修正依赖 + java 21）
- `src/main/resources/application.yml` + `application-test.yaml` + `db/migration/V1__init.sql` + `V2__seed_admin.sql`
- fun.hatsumi.chatbackend 下 9 个业务模块（common/config/auth/user/chat/file/network/rtc/admin）
- `D:\learning\chat_room\probe-client`（独立 Maven 项目）
- 文档偏差说明（Java 21、包名、Flyway starter 修正）追加到 doc/

### Definition of Done
- [ ] `mvnw.cmd clean test package` 在 JDK 21 下全绿
- [ ] 文档第 20 章"后端完成定义"全部条目满足
- [ ] 文档第 16 章测试清单全部有对应 JUnit 用例
- [ ] probe-client 可对本地 9001/9002/9101/9102 实测并输出 CSV

### Must Have
- BCryptPasswordEncoder(10) 密码哈希；java-jwt HMAC256 Token（iss=chatroom-lab、12h）
- JWT secret 从 `CHATROOM_JWT_SECRET` 环境变量读取，启动时 <32 字节直接失败
- WS 首帧 AUTH（5s 窗口）、重复连接踢旧、20s 心跳/60s 清理、发送串行化
- 分块上传：2MiB、X-Chunk-SHA256（错→422）、幂等、20% 差错模拟（可固定种子）、原子合并、Range 下载
- TCP/UDP 二进制协议（magic 0x43484154、Big Endian、LengthFieldBasedFrameDecoder）、UDP ≤1200B
- 网络测试目标限本机 + RFC1918 私有网段
- 统一响应 `{code,message,data}` + 全局异常（400/401/403/404/409/422/500）
- 优雅关闭：Netty Channel/EventLoopGroup、线程池、临时文件

### Must NOT Have (Guardrails)
- ❌ `spring-boot-starter-security`（任何形式）；❌ Spring Data JPA / JpaRepository；❌ OAuth2/Refresh Token/Session 集群
- ❌ 明文密码入库或入日志；❌ MD5/SHA-1/裸 SHA-256 存密码；❌ Token 进 URL Query 或日志
- ❌ SQL 字符串拼接或 `${}`（一律 `#{}`）
- ❌ `Files.readAllBytes()` 处理大文件（分块/流式）
- ❌ 修改 `room-chat/`（前端）任何文件
- ❌ 伪造实验/性能数据（CSV 必须来自真实运行）
- ❌ Java preview feature；❌ 引入文档外重型框架
- ❌ Controller 直接调 Mapper；❌ WebSocket Handler 拼 SQL
- ❌ 过度抽象/过度注释等 AI slop（保持课程项目直白风格）

---

## Verification Strategy (MANDATORY)

> **ZERO HUMAN INTERVENTION** — 全部验证由执行 agent 完成。
> 环境：Windows PowerShell 5.1；Maven 用 `mvnw.cmd`；HTTP 用 `curl.exe`（PowerShell 5.1 下注意转义）或 Invoke-RestMethod。

### Test Decision
- **Infrastructure exists**: 部分（spring-boot-starter-test 脚手架已有，需补充 webmvc-test 切片）
- **Automated tests**: Tests-with-implementation（每任务同步交付 JUnit 用例，文档第 16 章为验收清单）
- **Framework**: JUnit 5 + Spring Boot Test（@SpringBootTest + @WebMvcTest + EmbeddedChannel/StandardWebSocketClient）

### QA Policy
- 每任务至少 1 个 happy path + 1 个 failure/edge QA 场景（下述模板）
- 证据存 `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`（文本输出/JSON 响应/CSV）
- 服务级验证统一模式：启动应用（`$env:CHATROOM_JWT_SECRET=...; mvnw.cmd spring-boot:run` 后台）→ curl 断言 → 停止
- DB 验证：优先 `mysql -uchatroom -pchatroom123 chatroom_lab -e "..."`；**若 mysql CLI 不在 PATH**（MySQL81 默认安装路径如 `C:\Program Files\MySQL\MySQL Server 8.1\bin\mysql.exe`），用完整路径调用；CLI 完全不可用时以 JUnit 集成测试断言作为等效证据并注明

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (B0 工程与数据库基础):
├── T1 pom 修正 + git init [quick]
├── T2 包结构 + 配置 + JWT 启动校验 + CORS [deep]
├── T3 Flyway V1 全部 6 表 + V2 admin 种子 [deep]
├── T4 统一响应 + 全局异常 + /api/health [quick]
├── T5 MyBatis-Plus 配置 + 测试基建 [deep]
└── T6 B0 验收：启动 + Flyway + 全量构建 [quick]

Wave 2 (B1 轻量登录):
├── T7 user 实体/mapper/service + BCrypt [deep]
├── T8 JwtService + JwtProperties [deep]
├── T9 AuthController + DTO 校验 [deep] (依赖 7,8)
├── T10 AuthInterceptor + UserContext + admin 检查 [deep] (依赖 8)
└── T11 B1 验收：认证全链路 + 无明文密码 [quick] (依赖 9,10)

Wave 3 (B2 WebSocket 聊天):
├── T12 chat_messages 实体/mapper/XML 分页 + 消息服务 [deep]
├── T13 WS 协议信封 + MessageType + 编解码 [quick]
├── T14 SessionRegistry + PresenceService + 心跳 [deep]
├── T15 WsAuthService 首帧 AUTH [deep] (依赖 8,13,14)
├── T16 ChatWebSocketHandler 路由/私聊/广播 [deep] (依赖 12,13,14,15)
└── T17 ServerNoticeService + B2 验收 e2e [unspecified-high] (依赖 16)

Wave 4 (B3 文件服务):
├── T18 file 三实体/mapper/条件查询 XML [deep]
├── T19 StorageService 路径安全 + 流式工具 [deep]
├── T20 上传会话 API + 缺块查询 [deep] (依赖 18)
├── T21 分块上传 + SHA-256 + 差错模拟 + 幂等 [deep] (依赖 19,20)
├── T22 合并/完整摘要/原子注册 [deep] (依赖 21)
├── T23 Range 下载 + 删除 + 文件列表 [deep] (依赖 18,19)
└── T24 过期清理定时任务 + B3 验收 [quick] (依赖 20,22)

Wave 5 (B4 网络实验 + probe-client):
├── T25 二进制协议编解码 + Netty TCP/UDP codec [deep]
├── T26 TcpEchoServer(9001) + UdpEchoServer(9002) + 生命周期 [deep] (依赖 25)
├── T27 延时测试 API + 统计 + CSV + 入库 [deep] (依赖 26)
├── T28 带宽测试 API (TCP/UDP) [deep] (依赖 26)
└── T29 probe-client 独立项目 [deep] (依赖 25，仅协议格式)

Wave 6 (B5 性能/WebRTC/管理):
├── T30 BlockingTcpEchoServer(9101) + NioTcpEchoServer(9102) [deep]
├── T31 压测客户端 + 基准 API + 指标 CSV [deep] (依赖 30)
├── T32 RTC 信令中转 (RTC_OFFER/ANSWER/ICE) [unspecified-high] (依赖 16)
├── T33 管理接口 + RuntimeSettings + 禁用踢线 [unspecified-high] (依赖 10,14,21)
└── T34 B5 收尾：优雅关闭全链路 + 全量构建 [deep] (依赖 31,32,33)

Wave FINAL (终审，4 并行):
├── F1 计划合规审计 [oracle]
├── F2 代码质量审查 [unspecified-high]
├── F3 真实 QA 全场景执行 [unspecified-high]
└── F4 范围保真检查 [deep]

Critical Path: T1→T5→T7/T8→T10→T16→T21→T22→T27→T30→T31→T34→F1-F4
Max Concurrent: 5 (Wave 1)
```

### Dependency Matrix（全量）

| 任务 | 依赖 | 被依赖（Blocks） |
|---|---|---|
| T1 | — | T2-T6, 全部后续 |
| T2 | T1 | T6, B1+ |
| T3 | T1 | T6, T7, T12, T18 |
| T4 | T1 | T6, 所有 Controller |
| T5 | T1, T3 | T6, T7, T12, T18 |
| T6 | T2-T5 | T7-T11 |
| T7 | T5, T3 | T9, T10, T11, T33 |
| T8 | T1 | T9, T10, T11, T15 |
| T9 | T7, T8 | T11 |
| T10 | T8, T7 | T11, T33 |
| T11 | T9, T10 | T12-T17 |
| T12 | T5 | T16, T17 |
| T13 | T1 | T15, T16 |
| T14 | T1 | T15, T16, T17, T33 |
| T15 | T8, T13, T14 | T16 |
| T16 | T12, T13, T14, T15 | T17, T32, T33 |
| T17 | T16 | T24 之后无 |
| T18 | T5, T3 | T20, T23 |
| T19 | T2 | T21, T22, T23 |
| T20 | T18 | T21, T24 |
| T21 | T19, T20 | T22, T33 |
| T22 | T21 | T24 |
| T23 | T18, T19 | T24 |
| T24 | T20, T22 | T34 |
| T25 | T1 | T26, T27, T28, T29 |
| T26 | T25 | T27, T28, T29 联调 |
| T27 | T26 | T34 |
| T28 | T26 | T34 |
| T29 | T25 | F3（联调验证） |
| T30 | T5 | T31, T34 |
| T31 | T30 | T34 |
| T32 | T16 | T34 |
| T33 | T10, T14, T16, T21 | T34 |
| T34 | T24, T27, T28, T31, T32, T33 | F1-F4 |
| F1-F4 | T34 + 全部 | — |

### Agent Dispatch Summary

- **Wave 1**: T1 → `quick`，T2/T3/T5 → `deep`，T4 → `quick`，T6 → `quick`
- **Wave 2**: T7/T8/T9/T10 → `deep`，T11 → `quick`
- **Wave 3**: T12/T14/T15/T16 → `deep`，T13 → `quick`，T17 → `unspecified-high`
- **Wave 4**: T18-T23 → `deep`，T24 → `quick`
- **Wave 5**: T25-T29 → `deep`
- **Wave 6**: T30/T31/T34 → `deep`，T32/T33 → `unspecified-high`
- **FINAL**: F1 → `oracle`，F2/F3 → `unspecified-high`，F4 → `deep`

所有 Java 任务统一注入 `java-dev` skill（命名/异常/Spring Boot 规范）。

### 用户前置条件（执行前须人工就绪）
1. ~~MySQL chatroom_lab/chatroom_lab_test 建库~~ → **已延后**（用户指令 2026-09-03："mysql 先不部署了，等到后续联调了再部署"）→ 见下方 "MySQL Deferred 执行模式"
2. 运行应用/QA 时设置 `$env:CHATROOM_JWT_SECRET`（≥32 字节，如 `dev-secret-0123456789abcdef0123456789abcdef`）
3. JDK 21 + Maven 3.8.8（已就绪）
4. 备注：本机检测到 MySQL81 服务运行中（3306 可达）——后续联调部署时可优先复用该实例，无需重新安装

---

## MySQL Deferred 执行模式（用户指令 2026-09-03）

> **背景**：用户指示 MySQL 延后部署，联调阶段再启用。代码构建现在开始，不阻塞。

### 执行规则（全任务适用）
1. **波次与任务顺序不变**，代码按计划全量推进（实体/Mapper/Service/Controller/WS/Netty 全部照常实现）
2. **DB 依赖集成测试**（@SpringBootTest 连库类）：统一加 JUnit5 `@Tag("db")`；pom surefire 配置 `excludedGroups=db` 自动跳过；跳过时在测试报告可见（非静默）。MySQL 就绪后移除门控跑全量
3. **DEFERRED-DB 标记协议**：每任务中依赖真实 MySQL 的验收项/QA 场景，写入 `.sisyphus/evidence/task-{N}-deferred.txt`（列明：验收项、原 QA 步骤、恢复条件）。任务完成条件放宽为：**代码完成 + 无 DB 可执行测试全绿 + deferred 清单记录完整**
4. **无 DB 期间必须照常验证**：编译、单元测试（纯逻辑）、组件级测试（mock mapper/独立实例化 Netty 服务）、probe-client 编译与 CLI 测试
5. **服务级启动冒烟（curl/WS 真实连接）**：DEFERRED-DB（context 启动需要 datasource）；质量由组件测试保证
6. Netty echo 服务（T26/T30）设计为**可独立实例化**（不依赖 Spring context 即可 start/stop 测试）——本就是良好设计，无额外成本

### 新增任务 T35：MySQL 集成恢复（用户部署 MySQL 后执行）
- 建库（chatroom_lab + chatroom_lab_test）+ 用户授权
- 移除 surefire db 门控 → `mvnw.cmd clean test` 全量（含全部 @Tag("db") 测试）
- 逐任务补跑 `.sisyphus/evidence/task-{N}-deferred.txt` 中的 DEFERRED-DB 验收
- 服务级 QA 全链路（启动冒烟 + 各任务真实 HTTP/WS 场景）
- 通过后进入 F 终审波次；**若终审时 MySQL 仍未部署**：F1/F3 的 DB 相关检查标记 DEFERRED-DB，最终报告显著注明"后端 DB 路径未经验证"

### 依赖矩阵增量
| 任务 | 依赖 | 被依赖 |
|---|---|---|
| T35 | T34 + **用户部署 MySQL** | F1-F4（条件依赖） |

---

## TODOs

- [ ] 1. pom.xml 修正 + git init

  **What to do**:
  - 修改 `pom.xml`：`<java.version>21</java.version>`（偏差记录：文档要求 25，本机仅有 JDK 21）
  - 删除依赖：`spring-boot-starter-batch-jdbc`、`spring-boot-starter-webflux`、`spring-boot-starter-batch-jdbc-test`、`spring-boot-starter-webflux-test`
  - 保留：`spring-boot-starter-webmvc`、`lombok`（含现有 annotationProcessorPaths 配置）
  - 新增依赖（版本按文档 2.1 节，BOM 管理的不写版本）：`spring-boot-starter-websocket`、`spring-boot-starter-validation`、`spring-boot-starter-actuator`、`spring-boot-starter-flyway`（**注意：Boot 4 必须用 starter，不用裸 flyway-core**）、`org.flywaydb:flyway-mysql`（先不写版本依赖 BOM，构建失败再加显式版本）、`com.baomidou:mybatis-plus-spring-boot4-starter:3.5.17`、`com.mysql:mysql-connector-j(runtime)`、`org.springframework.security:spring-security-crypto`、`com.auth0:java-jwt:4.6.0`、`io.netty:netty-all`、`spring-boot-starter-test(test)`
  - 测试切片：保留/添加 `spring-boot-starter-test`；如需 @WebMvcTest 切片验证 `spring-boot-starter-webmvc-test` 是否必要（Boot 4 中 @WebMvcTest 位于 spring-boot-webmvc-test 模块，starter-test 可能已传递——以实际编译结果为准）
  - `git init` + 确认 `.gitignore` 覆盖 `data/`、`.env`、`target/`、`*.log`，首次提交现有脚手架
  - 运行 `mvnw.cmd clean compile` 验证依赖解析（首次会下载，可能较慢）

  **Must NOT do**:
  - 不引入 `spring-boot-starter-security`、JPA、其他文档外依赖
  - 不为 BOM 已管理的依赖写版本号（仅 MyBatis-Plus 3.5.17 和 java-jwt 4.6.0 显式版本）

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 单文件配置修改 + git init，范围小且明确
  - **Skills**: [`java-dev`]
    - `java-dev`: Maven 依赖管理规范
  - **Skills Evaluated but Omitted**: 无（纯构建配置任务）

  **Parallelization**:
  - **Can Run In Parallel**: NO（全计划根依赖）
  - **Parallel Group**: Wave 1 起点（先行）
  - **Blocks**: T2-T6 及全部后续
  - **Blocked By**: None

  **References**:
  - `pom.xml`（现有全文）— 待修改的目标文件，注意保留 parent 4.1.1 和 lombok annotationProcessorPaths
  - `doc/聊天室系统-后端OpenCode实施文档.md:54-131` — 文档 2.1 节 pom 核心内容（其中 flyway-core 部分按 librarian 验证结论修正为 starter）
  - Maven Central: `https://central.sonatype.com/artifact/com.baomidou/mybatis-plus-spring-boot4-starter/3.5.17` — MyBatis-Plus Boot4 starter 坐标验证

  **Acceptance Criteria**:
  - [ ] `mvnw.cmd clean compile` 输出 BUILD SUCCESS
  - [ ] `mvnw.cmd dependency:tree -q | Select-String "security-starter|data-jpa"` 无输出（无禁止依赖）
  - [ ] `git log --oneline` 至少 1 条提交

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: 依赖解析与编译成功（happy path）
    Tool: Bash (PowerShell)
    Preconditions: JDK 21 + Maven 3.8.8 可用（已验证）
    Steps:
      1. 运行 `mvnw.cmd clean compile`
      2. 检查输出包含 "BUILD SUCCESS"
      3. 运行 `mvnw.cmd dependency:tree` 并搜索确认存在: mybatis-plus-spring-boot4-starter:3.5.17, java-jwt:4.6.0, netty-all, spring-boot-starter-flyway, flyway-mysql, spring-security-crypto, mysql-connector-j
      4. 确认输出中不存在: spring-boot-starter-security, spring-boot-starter-webflux, spring-boot-starter-batch-jdbc, spring-data-jpa
    Expected Result: BUILD SUCCESS + 全部期望依赖在树中 + 禁止依赖全部缺席
    Failure Indicators: 依赖解析失败（检查 Maven Central 可达性）；出现禁止依赖
    Evidence: .sisyphus/evidence/task-1-dep-tree.txt

  Scenario: 禁止依赖检查（guardrail 验证）
    Tool: Bash (PowerShell)
    Steps:
      1. `mvnw.cmd dependency:tree > dep.txt`
      2. `Select-String -Path dep.txt -Pattern "starter-security|data-jpa|webflux|batch-jdbc"` 应无匹配
    Expected Result: 零匹配
    Failure Indicators: 任一匹配出现
    Evidence: .sisyphus/evidence/task-1-forbidden-deps-check.txt
  ```

  **Commit**: YES
  - Message: `build(pom): fix dependencies for Boot 4 stack (webmvc/websocket/flyway/mybatis-plus/netty/jwt) + git init`
  - Files: pom.xml, .gitignore
  - Pre-commit: `mvnw.cmd clean compile -q`

- [ ] 2. 包结构 + 完整配置 + JWT 启动校验 + CORS

  **What to do**:
  - 创建包结构（在 `fun.hatsumi.chatbackend` 下，对应文档 3 节）：`common/api`、`common/exception`、`common/util`、`config`、`auth/controller`、`auth/dto`、`auth/interceptor`、`auth/service`、`user/entity`、`user/mapper`、`user/service`、`chat/entity`、`chat/mapper`、`chat/service`、`chat/websocket`、`chat/protocol`、`file/controller`、`file/entity`、`file/mapper`、`file/service`、`file/storage`、`network/protocol`、`network/tcp`、`network/udp`、`network/benchmark`、`network/service`、`rtc`、`admin`（各包放 package-info 或留空待后续任务填充，仅 config/common 立即有实现）
  - 修改启动类 `ChatBackendApplication` 添加 `@MapperScan({"fun.hatsumi.chatbackend.user.mapper","fun.hatsumi.chatbackend.chat.mapper","fun.hatsumi.chatbackend.file.mapper"})`（network 模块无 mapper 则不列；`network.mapper` 不创建）
  - 重写 `application.yaml` → `application.yml`（删除空壳 yaml）：按文档 4 节完整配置（server.port=8080、datasource 指向 chatroom_lab、multipart 2GB、mybatis-plus mapper-locations/type-aliases（**按实际包名** `fun.hatsumi.chatbackend.user.entity,fun.hatsumi.chatbackend.chat.entity,fun.hatsumi.chatbackend.file.entity`）、`chatroom.jwt.*`、`chatroom.storage-root=./data/storage`、`chatroom.log-root=./data/logs/network-tests`、`chatroom.error-simulation-rate=0.20`、`chatroom.error-simulation-seed=`（空=随机，测试时设固定值）、`chatroom.tcp-port=9001`、`chatroom.udp-port=9002`、`chatroom.blocking-benchmark-port=9101`、`chatroom.nio-benchmark-port=9102`，另加 `chatroom.ws.auth-timeout-seconds=5`、`chatroom.ws.heartbeat-seconds=20`、`chatroom.ws.idle-timeout-seconds=60`、`chatroom.ws.max-text-message-size=65536`）
  - 新建 `config/ChatroomProperties.java`（@ConfigurationProperties("chatroom")，含上述全部字段 + getter）与 `config/JwtProperties`（issuer/secret/expireHours）
  - 新建 `config/JwtSecretValidator.java`：实现 `InitializingBean` 或 `@PostConstruct`，secret 为空或 UTF-8 字节数 <32 时抛异常终止启动，错误信息含 "CHATROOM_JWT_SECRET"（环境变量占位 `${CHATROOM_JWT_SECRET:}`）
  - 新建 `config/CorsConfig.java`：允许来源 `http://localhost:5173`、`http://127.0.0.1:5173`、`http://localhost:4173`，方法 GET/POST/PUT/DELETE/OPTIONS，头 Authorization/Content-Type/X-Chunk-SHA256，`applyPermitDefault` 不需要 credentials（Token 走 header）
  - 新建 `config/WebSocketConfig` 占位（后续 T16 完善，本任务仅包结构）

  **Must NOT do**:
  - 不引入任何 preview 语法
  - 不把 secret 默认值写成有效密钥（必须从环境变量取，默认空 → 启动失败）
  - 不使用文档中的 `com.hlx.chatroom` 包名

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 多文件结构性任务，配置项较多且需严谨的类型绑定
  - **Skills**: [`java-dev`]
    - `java-dev`: Spring Boot 配置与命名规范
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T3/T4/T5 并行）
  - **Parallel Group**: Wave 1
  - **Blocks**: T6 及 Wave 2+ 所有配置消费方
  - **Blocked By**: T1

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:210-249` — 4 节 application.yml 完整模板（type-aliases-package 需改为 fun.hatsumi.chatbackend.*）
  - `doc/聊天室系统-后端OpenCode实施文档.md:154-206` — 3 节目录结构（com.hlx.chatroom 映射为 fun.hatsumi.chatbackend）
  - `src/main/resources/application.yaml`（现有空壳，将被替换为 application.yml）

  **Acceptance Criteria**:
  - [ ] 目录结构与文档 3 节一一对应（包名前缀替换）
  - [ ] `mvnw.cmd clean compile` BUILD SUCCESS
  - [ ] 有无环境变量两种启动行为验证（见 QA）

  **QA Scenarios**:

  ```
  Scenario: JWT secret 校验逻辑（happy path，MySQL Deferred 模式下用纯单元验证）
    Tool: Bash (JUnit)
    Steps:
      1. `mvnw.cmd test -Dtest=JwtSecretValidatorTest`
      2. 断言：secret=null → 抛异常且消息含 CHATROOM_JWT_SECRET；secret="short"(<32B) → 抛异常；secret=32+字节 → 通过
    Expected Result: 三分支断言通过
    Failure Indicators: 任一分支不抛/抛错消息
    Evidence: .sisyphus/evidence/task-2-jwt-secret-validator-unit.txt

  Scenario: 启动级 fail-fast 验证（DEFERRED-DB：需完整 context 启动含 datasource；MySQL 就绪后由 T35 补跑）
    Tool: DEFERRED-DB
    Steps:
      1. 无 secret 启动 → 进程失败且日志含 CHATROOM_JWT_SECRET
    Deferred 条件: MySQL 部署后
    Evidence: .sisyphus/evidence/task-2-deferred.txt（记录本项）
  ```

  **Commit**: YES
  - Message: `chore(config): package structure, application.yml, jwt secret validation, cors`
  - Files: src/main/java/**（新增包与配置类）、src/main/resources/application.yml（删除 application.yaml）
  - Pre-commit: `mvnw.cmd clean compile -q`

- [ ] 3. Flyway V1 全部 6 张表 + V2 admin 种子

  **What to do**:
  - 新建 `src/main/resources/db/migration/V1__init.sql`，按文档 5.1 节创建：
    - `users`（id BIGINT AUTO_INCREMENT PK、username VARCHAR(50) UNIQUE、display_name VARCHAR(50)、password_hash VARCHAR(100)、role VARCHAR(20) NOT NULL DEFAULT 'USER'、enabled TINYINT(1) NOT NULL DEFAULT 1、created_at/updated_at DATETIME(3)，InnoDB utf8mb4）
    - `chat_messages`（id PK、sender_id/receiver_id BIGINT NULL、chat_type VARCHAR(20)、content TEXT、text_color VARCHAR(16) NULL、sent_at DATETIME(3)，索引 sender_id/receiver_id/sent_at）
    - `stored_files`（id PK、owner_id BIGINT NULL、scope VARCHAR(10)、original_name VARCHAR(255)、stored_name VARCHAR(255)、relative_path VARCHAR(512)、size_bytes BIGINT、sha256 CHAR(64)、created_at，索引 owner_id/scope）
    - `upload_sessions`（id PK、upload_id VARCHAR(64) UNIQUE、owner_id、scope、file_name、file_size BIGINT、chunk_size INT、total_chunks INT、file_sha256 CHAR(64) NULL、status VARCHAR(20)、expire_at DATETIME(3)、created_at）
    - `upload_chunks`（id PK、upload_id、chunk_index INT、chunk_size、chunk_sha256 CHAR(64)、created_at，**UNIQUE KEY uk_upload_chunk(upload_id, chunk_index)**）
    - `network_test_runs`（id PK、test_type、protocol、target_host、target_port、params JSON 或 VARCHAR、started_at/finished_at、avg/min/max/方差/stddev/P50/P95、throughput_mbps、packet_loss_rate、scenario_name、csv_path，样本原始数据走 CSV 不入库）
  - 新建 `V2__seed_admin.sql`：插入 admin 账号（username=`admin`、display_name=管理员、role=ADMIN、enabled=1、password_hash=**预计算的 BCrypt 哈希**，对应明文 `admin123456`）。生成哈希方式：任务中写一次性 Java 测试或 main 用 `new BCryptPasswordEncoder(10).encode("admin123456")` 生成后写入 SQL（执行 agent 自行生成，勿用占位符）
  - 数据库校验：应用启动后 Flyway 自动执行（本任务验证方式见 QA）

  **Must NOT do**:
  - 不启用任何 ORM 自动建表（无 ddl-auto）
  - V2 中不存明文密码（只存 $2a$ 开头的 BCrypt 哈希）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 表结构正确性影响全项目，字段/索引需严谨对照文档
  - **Skills**: [`java-dev`]
    - `java-dev`: 数据库规范
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T2/T4/T5 并行）
  - **Parallel Group**: Wave 1
  - **Blocks**: T6, T7, T12, T18
  - **Blocked By**: T1

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:253-289` — 5.1 节核心表定义（逐字段对照）
  - `doc/聊天室系统-后端OpenCode实施文档.md:291-301` — 5.2 节 MyBatis-Plus 规则（唯一索引/规则来源）

  **Acceptance Criteria**:
  - [ ] V1/V2 SQL 语法正确（本地 mysql 执行或经 Flyway 验证）
  - [ ] 6 张表全部存在且字段齐备
  - [ ] admin 账号存在且 password_hash 以 `$2a$` 开头

  **QA Scenarios**:

  ```
  Scenario: Flyway 迁移执行成功（happy path）
    Tool: Bash (PowerShell + mysql CLI)
    Preconditions: 用户已创建 chatroom 库与账号；CHATROOM_JWT_SECRET 已设；T2 配置就绪（若 T2 未完可单独用 mysql 命令先手动执行 SQL 验证语法）
    Steps:
      1. 设置 secret 后 `mvnw.cmd spring-boot:run` 后台启动，等待日志 "Successfully applied 2 migrations"（或 flyway 日志 "Migrating schema ... to version 2"）
      2. `mysql -uchatroom -pchatroom123 chatroom_lab -e "SHOW TABLES;"`
      3. 断言输出含 users/chat_messages/stored_files/upload_sessions/upload_chunks/network_test_runs/flyway_schema_history
      4. `mysql -uchatroom -pchatroom123 chatroom_lab -e "SELECT username,role,LEFT(password_hash,4) FROM users;"`
      5. 断言一行 admin/ADMIN/$2a$
    Expected Result: 7+1 张表 + admin 种子正确
    Failure Indicators: 表缺失/哈希非 $2a$
    Evidence: .sisyphus/evidence/task-3-flyway-tables.txt

  Scenario: 重复启动幂等（迁移不重复执行）
    Tool: Bash
    Steps:
      1. 再次启动应用
      2. 检查 flyway_schema_history 仍只有 version 1、2 各一行
    Expected Result: 无重复迁移/无报错
    Evidence: .sisyphus/evidence/task-3-flyway-idempotent.txt
  ```

  **Commit**: YES
  - Message: `feat(db): flyway V1 schema for all 6 tables + V2 admin seed`
  - Files: src/main/resources/db/migration/V1__init.sql、V2__seed_admin.sql
  - Pre-commit: `mvnw.cmd clean compile -q`

- [ ] 4. 统一响应 + 全局异常 + /api/health

  **What to do**:
  - 新建 `common/api/ApiResponse<T>`：字段 `code`(int)、`message`(String)、`data`(T)；静态工厂 `ok(data)`（code=0, message="success"）、`error(code, message)`
  - 新建 `common/exception/BusinessException`：携带 code + message + HTTP 状态（如 400/401/403/404/409/422）；子类可后续按需（不强制过度设计）
  - 新建 `common/exception/GlobalExceptionHandler`（@RestControllerAdvice）：
    - BusinessException → 对应 HTTP 状态 + ApiResponse
    - MethodArgumentNotValidException / ConstraintViolationException → 400 + 首条字段错误信息
    - HttpRequestMethodNotSupportedException → 405 或按文档归 400
    - 未知 Exception → 500 + "服务器内部错误"，**不返回堆栈**，log.error 记录完整堆栈
  - 新建 `common/controller/HealthController`（或 common/api 下）：`GET /api/health` 返回 `ApiResponse.ok(Map.of("status","UP"))`，不做 DB 检查（保持轻量；actuator 已另行提供深度健康检查）

  **Must NOT do**:
  - 不在 500 响应中返回堆栈信息
  - 不引入额外响应包装框架

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 3-4 个小类，模式标准且清晰
  - **Skills**: [`java-dev`]
    - `java-dev`: 异常处理规范（统一响应/全局异常最佳实践直接对应）
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T2/T3/T5 并行）
  - **Parallel Group**: Wave 1
  - **Blocks**: T6、所有后续 Controller 任务
  - **Blocked By**: T1

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:303-323` — 6 节统一 REST 响应格式与状态码约定
  - `doc/聊天室系统-后端OpenCode实施文档.md:383` — /api/health 拦截器排除路径（本任务定义该端点）

  **Acceptance Criteria**:
  - [ ] `GET /api/health` 返回 `{"code":0,"message":"success","data":{"status":"UP"}}`
  - [ ] 单元测试：ok/error 工厂、BusinessException 映射、未知异常 500 无堆栈

  **QA Scenarios**:

  ```
  Scenario: 健康端点返回统一格式（happy path）
    Tool: Bash (curl)
    Preconditions: T1-T3 完成；应用可启动（secret 已设、MySQL 就绪）
    Steps:
      1. 后台启动应用，等待日志 "Started ChatBackendApplication"（最多 120s）
      2. `curl.exe -s http://localhost:8080/api/health`
      3. 断言 JSON 恰为 {"code":0,"message":"success","data":{"status":"UP"}}
      4. 停止应用（Stop-Process 或 Ctrl+C 等价）
    Expected Result: 精确 JSON 匹配，HTTP 200
    Failure Indicators: 404/500/格式不符
    Evidence: .sisyphus/evidence/task-4-health-endpoint.txt

  Scenario: 未知异常返回 500 且无堆栈
    Tool: Bash (curl) + 临时触发端点（用测试代替亦可）
    Steps:
      1. 编写并运行 JUnit 测试 MockMvc：Controller 抛 RuntimeException → 断言 500 + body 无 "at com."/"Exception" 堆栈特征 + message="服务器内部错误"
    Expected Result: 测试通过（断言响应体不含堆栈）
    Failure Indicators: 响应含 stacktrace
    Evidence: .sisyphus/evidence/task-4-exception-no-stacktrace.txt（mvn test 输出）
  ```

  **Commit**: YES
  - Message: `feat(common): unified ApiResponse + global exception handler + health endpoint`
  - Files: src/main/java/fun/hatsumi/chatbackend/common/**
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 5. MyBatis-Plus 配置 + 测试基建

  **What to do**:
  - 新建 `config/MybatisPlusConfig`：`@Configuration` + `@MapperScan`（若启动类已有则只保留一处）+ `MybatisPlusInterceptor` Bean 注册 `PaginationInnerInterceptor(DbType.MYSQL)`
  - 确认 `application.yml` 中 `mapper-locations: classpath*:/mapper/**/*.xml`、`map-underscore-to-camel-case: true`、`id-type: auto` 生效
  - 新建 `src/main/resources/mapper/` 目录（XML 驻留，后续 T12/T18 填充）
  - 测试基建：
    - `src/test/resources/application-test.yaml`：datasource URL 指向 `chatroom_lab_test`（加 `createDatabaseIfNotExist=true`）、secret 用 32+ 字节测试值、心跳/超时缩短（ws.auth-timeout-seconds=2、heartbeat=2、idle-timeout=4）、error-simulation seed=42
    - 测试基类 `fun.hatsumi.chatbackend.support.IntegrationTestBase`：`@SpringBootTest` + `@ActiveProfiles("test")`，提供 @Transactional 回滚说明（Mapper 集成测试用 @Sql 或 @BeforeEach 清理关键表）
    - 删除/改造现有 `ChatBackendApplicationTests`（contextLoads 用 test profile，避免依赖手动环境）
  - 跑通：`mvnw.cmd test`（此时仅 context load + health 测试，但验证 Flyway 在 test 库自动建表）

  **Must NOT do**:
  - 测试不连开发库 chatroom_lab（必须 chatroom_lab_test）
  - 不在测试 yaml 中提交真实生产 secret（测试值固定字符串即可）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 测试基建决定后续 12 个任务的验证方式，需要严谨设计 profile 隔离
  - **Skills**: [`java-dev`]
    - `java-dev`: Spring Boot 测试规范
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T2/T3/T4 并行；application-test.yaml 为独立文件，与 T2 的主 yml 无文件冲突）
  - **Parallel Group**: Wave 1
  - **Blocks**: T6, T7, T12, T18 及全部含测试任务
  - **Blocked By**: T1, T3（test 库 Flyway 建表依赖迁移脚本；主 yml 由 T2 并行完成，提交时序无冲突）

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:291-301` — 5.2 节 MyBatis-Plus 规则（分页拦截器/参数绑定/Flyway 唯一来源）
  - baomidou 官方分页插件：`https://baomidou.com/en/plugins/pagination/` — PaginationInnerInterceptor 注册方式
  - `src/test/java/fun/hatsumi/chatbackend/ChatBackendApplicationTests.java`（现有）— 需改造为 test profile

  **Acceptance Criteria**:
  - [ ] `mvnw.cmd test` 全绿（含 contextLoads @ test profile）
  - [ ] `chatroom_lab_test` 库中 6 张表由 Flyway 自动建立
  - [ ] 开发库 chatroom_lab 不被测试污染（表数据无测试残留——通过 profile 隔离保证）

  **QA Scenarios**:

  ```
  Scenario: 测试运行隔离验证（happy path）
    Tool: Bash (PowerShell + mysql)
    Steps:
      1. `mysql -uchatroom -pchatroom123 chatroom_lab -e "SELECT COUNT(*) FROM users;"` 记录基线数 N
      2. `mvnw.cmd test`
      3. 再次查询 chatroom_lab users 行数 = N（不变）
      4. `mysql -uchatroom -pchatroom123 -e "SHOW DATABASES LIKE 'chatroom_lab_test';"` 存在
    Expected Result: 测试全部通过 + 开发库零污染 + test 库自动创建并建表
    Failure Indicators: 测试失败/开发库行数变化
    Evidence: .sisyphus/evidence/task-5-test-isolation.txt

  Scenario: 缺 MySQL 时测试明确失败（edge，验证测试真的连库）
    Tool: Bash
    Steps:
      1. 临时将 application-test.yaml URL 端口改为 3307（本地无服务）
      2. `mvnw.cmd test` 断言连接错误而非静默跳过
      3. 改回 3306
    Expected Result: 连接失败错误出现
    Evidence: .sisyphus/evidence/task-5-db-required.txt
  ```

  **Commit**: YES
  - Message: `feat(config): mybatis-plus pagination + test infrastructure with isolated test db`
  - Files: config/MybatisPlusConfig.java、src/test/resources/application-test.yaml、test 基类
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 6. B0 阶段验收：启动 + Flyway + 全量构建

  **What to do**:
  - 端到端验收（不自写新功能，只验证 T1-T5 成果）：
    1. `mvnw.cmd clean test package` 全绿
    2. 生产 profile 启动：`$env:CHATROOM_JWT_SECRET="dev-secret-0123456789abcdef0123456789abcdef"; mvnw.cmd spring-boot:run` → 日志含 Flyway 迁移成功 + Started
    3. `curl http://localhost:8080/api/health` 200
    4. `curl http://localhost:8080/actuator/health` 200（actuator 就绪）
  - 若发现问题（如 flyway-mysql 版本问题、BOM 冲突），在本任务内修复
  - 记录 B0 完成清单，输出"剩余任务"摘要（B1-B5 待办）

  **Must NOT do**:
  - 不提前实现登录/聊天/文件/Netty 任何功能
  - 不为通过验收修改测试断言来"绕过"问题

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 纯验证型任务，步骤明确
  - **Skills**: [`java-dev`]
    - `java-dev`: 构建验收规范
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖 T2-T5 全部完成）
  - **Parallel Group**: Wave 1 收尾
  - **Blocks**: T7-T34（B0 是后续阶段的闸门）
  - **Blocked By**: T2, T3, T4, T5

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:637-641` — 阶段 B0 验收标准原文
  - `doc/聊天室系统-后端OpenCode实施文档.md:722-729` — 每阶段运行命令（Windows 用 mvnw.cmd）

  **Acceptance Criteria**:
  - [ ] `mvnw.cmd clean test package` BUILD SUCCESS
  - [ ] Flyway 日志确认 V1/V2 应用
  - [ ] /api/health 与 /actuator/health 均 200
  - [ ] 验收报告写入 evidence

  **QA Scenarios**:

  ```
  Scenario: B0 阶段完整验收（happy path）
    Tool: Bash (PowerShell)
    Preconditions: T1-T5 全部完成；MySQL 两库就绪
    Steps:
      1. `mvnw.cmd clean test package` → 断言 BUILD SUCCESS 且 target/*.jar 生成
      2. 设 secret 启动应用 → 等待 "Successfully applied 2 migrations" + "Started ChatBackendApplication"
      3. `curl.exe -s http://localhost:8080/api/health` 断言 code=0
      4. `curl.exe -s http://localhost:8080/actuator/health` 断言含 "UP"
      5. 停止应用
    Expected Result: 4 项全过
    Failure Indicators: 任一步失败
    Evidence: .sisyphus/evidence/task-6-b0-acceptance.txt

  Scenario: 打包产物可运行（edge）
    Tool: Bash
    Steps:
      1. `java -jar target\chatBackend-0.0.1-SNAPSHOT.jar`（设 secret）→ 启动成功 → health 200 → 停止
    Expected Result: jar 独立可运行
    Evidence: .sisyphus/evidence/task-6-jar-runnable.txt
  ```

  **Commit**: YES（若 B0 验收产生修复）
  - Message: `test(b0): phase acceptance and fixes`
  - Files: 视修复内容
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 7. user 模块：实体 + Mapper + UserService（BCrypt）

  **What to do**:
  - `user/entity/UserEntity`：@TableName("users") + @TableId(type=IdType.AUTO)；字段 id/username/displayName/passwordHash/role/enabled/createdAt/updatedAt；Lombok @Data
  - `user/mapper/UserMapper extends BaseMapper<UserEntity>`；`user/service/UserService`（可继承 ServiceImpl，但核心业务写在显式方法）
  - UserService 方法：
    - `register(username, displayName, password)`：查重（存在→BusinessException 409 "用户名已存在"）；`new BCryptPasswordEncoder(10).encode(password)`；插入；创建 `data/storage/users/{userId}` 目录；返回脱敏 UserDTO（id/username/displayName/role/enabled）
    - `verifyLogin(username, password)`：按 username 查询；不存在/enabled=0/matches 失败统一抛 401 "用户名或密码错误"（**不区分暴露用户是否存在**）；成功返回 UserEntity
    - `getEnabledUserById(Long id)`：供拦截器复查 enabled
  - `user/dto/UserDTO`（注册/登录响应共用）
  - 单元/集成测试：注册成功（DB 中 password_hash ≠ 明文且 $2a$ 开头）、重复注册 409、正确密码登录成功、错误密码 401、禁用用户 401、不存在用户 401
  - 注意：BCryptPasswordEncoder 作为 @Bean 注册（strength=10），Service 注入使用

  **Must NOT do**:
  - 不在日志中输出原密码（log 只记 username）
  - 不把 UserEntity 直接作为响应（脱敏 DTO）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 核心业务逻辑 + DB 访问 + 安全语义（错误信息统一）
  - **Skills**: [`java-dev`]
    - `java-dev`: 分层规范（Controller 不调 Mapper）、异常处理
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T8 并行）
  - **Parallel Group**: Wave 2
  - **Blocks**: T9, T10, T11, T33
  - **Blocked By**: T5, T3（users 表）

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:340-360` — 7.2/7.3 节注册登录流程（查重→哈希→存储目录；统一失败文案）
  - `doc/聊天室系统-后端OpenCode实施文档.md:675-685` — 16.1 登录测试清单
  - Spring Security Crypto PasswordEncoder：`https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/crypto/password/PasswordEncoder.html`

  **Acceptance Criteria**:
  - [ ] 上述 6 个测试用例全部存在且通过
  - [ ] BCrypt Bean 一次注册多处注入

  **QA Scenarios**:

  ```
  Scenario: 注册-登录往返（happy path）
    Tool: Bash (JUnit via mvnw) + mysql 验证
    Steps:
      1. `mvnw.cmd test -Dtest=UserServiceTest`
      2. 测试断言：注册后 users 行 password_hash 匹配 `^\$2[aby]\$10\$`
      3. 测试断言：encoder.matches(raw, hash) 为 true
    Expected Result: 全部通过，测试输出含断言明细
    Failure Indicators: 任何用例红
    Evidence: .sisyphus/evidence/task-7-user-service-test.txt

  Scenario: 重复用户名 409 + 统一登录失败文案（edge）
    Tool: Bash (JUnit)
    Steps:
      1. 测试：同 username 二次注册 → BusinessException code 对应 409
      2. 测试：错误密码 → 401 且 message="用户名或密码错误"；不存在用户同文案（字符串完全相等）
    Expected Result: 断言精确匹配文案（防用户枚举）
    Evidence: .sisyphus/evidence/task-7-dup-and-uniform-failure.txt
  ```

  **Commit**: YES
  - Message: `feat(user): user entity/mapper/service with BCrypt hashing`
  - Files: user/**、config 中 BCrypt Bean
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 8. JwtService + JwtProperties

  **What to do**:
  - `auth/service/JwtService`（依赖 JwtProperties）：
    - `issue(UserEntity)`：JWT.create()；withIssuer("chatroom-lab")、withSubject(userId 字符串)、withClaim("username",…)、withClaim("role",…)、withIssuedAt(now)、withExpiresAt(now + 12h，读配置 expire-hours)；Algorithm.HMAC256(secret)
    - `verify(String token)`：JWT.require(algorithm).withIssuer(issuer).acceptLeeway(5).build().verify(token)；失败区分 TokenExpiredException（401 "Token 已过期"）与其他 JWTVerificationException（401 "Token 无效"）；成功返回 DecodedJWT
  - 配置沿用 T2 的 JwtProperties（issuer/secret/expireHours）
  - 单元测试：签发→解析 claims 正确（sub/username/role/iss/exp-iat=12h）；过期 token（手工构造 13h 前 iat/exp 或 exp 过去）→ TokenExpiredException 映射 401；伪造签名 token（换 secret 签发）→ SignatureVerificationException 映射 401；错误 issuer → 401
  - 测试中生成 token 用独立 JwtService 实例（不同 secret/issuer 构造）

  **Must NOT do**:
  - JWT claims 不放密码哈希/文件路径/聊天内容（只放文档 7.3 节最小集）
  - 不自实现 JWT 编解码（必须用 java-jwt）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 安全敏感，异常映射与 claims 精确性要求高
  - **Skills**: [`java-dev`]
    - `java-dev`: 异常分层与安全规范
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T7 并行）
  - **Parallel Group**: Wave 2
  - **Blocks**: T9, T10, T11, T15
  - **Blocked By**: T1（java-jwt 依赖）, T2（JwtProperties）

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:362-371` — JWT Claims 清单
  - `doc/聊天室系统-后端OpenCode实施文档.md:675-685` — 16.1 JWT 测试项（签名/issuer/过期/伪造）
  - java-jwt README：`https://github.com/auth0/java-jwt` — HMAC256 + require + acceptLeeway API

  **Acceptance Criteria**:
  - [ ] 签发/验证/过期/伪造/issuer 五类测试全部通过
  - [ ] secret <32B 启动失败已在 T2 覆盖（本任务不重复）

  **QA Scenarios**:

  ```
  Scenario: Token 签发与解析往返（happy path）
    Tool: Bash (JUnit)
    Steps:
      1. `mvnw.cmd test -Dtest=JwtServiceTest`
      2. 断言 issue() 产物 verify() 后 sub=username role 正确
      3. 断言 exp-iat ≈ 43200s（12h，允许 ±60s）
    Expected Result: 全绿
    Evidence: .sisyphus/evidence/task-8-jwt-roundtrip.txt

  Scenario: 伪造/过期 token 被拒（edge）
    Tool: Bash (JUnit)
    Steps:
      1. 测试用不同 secret 签发 token → verify 抛 SignatureVerificationException → 业务映射 401
      2. 测试构造 exp=now-1h → TokenExpiredException → 401 "Token 已过期"
      3. 测试 issuer="evil" → 401
    Expected Result: 三种拒绝路径全部断言通过
    Evidence: .sisyphus/evidence/task-8-jwt-reject-paths.txt
  ```

  **Commit**: YES
  - Message: `feat(auth): JwtService with HMAC256 issue/verify`
  - Files: auth/service/JwtService.java 及测试
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 9. AuthController：注册 + 登录

  **What to do**:
  - `auth/dto/RegisterRequest`（@NotBlank username 3-50 字符、@NotBlank displayName ≤50、@NotBlank @Size(min=6,max=32) password）；`LoginRequest`（@NotBlank username/password）
  - `auth/controller/AuthController`：
    - `POST /api/auth/register` → UserService.register → ApiResponse.ok(UserDTO)
    - `POST /api/auth/login` → verifyLogin → JwtService.issue → ApiResponse.ok(LoginDTO{token, userId, username, displayName, role})（**字段名固定**：前端契约 token/userId/username/displayName/role）
  - @WebMvcTest 切片测试（MockBean UserService/JwtService）：参数校验失败 400（空 username、密码 5 字符）；注册 409；登录 401；成功 200 响应结构断言
  - Controller 只做参数/响应，不碰 Mapper

  **Must NOT do**:
  - 不返回 password_hash 任何形式
  - 不在响应中区分"用户不存在/密码错误"

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 对外契约（前端联调字段名）需精确
  - **Skills**: [`java-dev`]
    - `java-dev`: Controller 分层与校验规范
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖 T7+T8）
  - **Parallel Group**: Wave 2 第二步
  - **Blocks**: T11
  - **Blocked By**: T7, T8, T4（ApiResponse）

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:342-371` — 7.2/7.3 API 定义
  - `doc/聊天室系统-后端OpenCode实施文档.md:306-323` — 响应格式
  - 前端契约：room-chat draft（登录后读 localStorage `chatroom_token`，响应须含 token 字段）

  **Acceptance Criteria**:
  - [ ] 切片测试：400/409/401/200 四类全部断言
  - [ ] 响应 JSON 字段与 LoginDTO 定义一致

  **QA Scenarios**:

  ```
  Scenario: 注册+登录真实 HTTP 往返（happy path）
    Tool: Bash (curl) — 集成测试亦可（@SpringBootTest 起真实服务）
    Preconditions: 应用运行中（test profile 或本地 dev）
    Steps:
      1. `curl.exe -s -X POST http://localhost:8080/api/auth/register -H "Content-Type: application/json" -d '{"username":"qauser1","displayName":"QA One","password":"pass123456"}'`
         断言 code=0 且 data.userId>0 且无 password 相关字段
      2. `curl.exe -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"qauser1","password":"pass123456"}'`
         断言 code=0 且 data.token 非空（JWT 三段式）
    Expected Result: 两步均 code=0，登录返回 token
    Failure Indicators: 409（未清理 qauser1）/400/500
    Evidence: .sisyphus/evidence/task-9-register-login-flow.txt

  Scenario: 参数校验失败（edge）
    Tool: Bash (curl)
    Steps:
      1. 注册 password="12345"（5 字符）→ 断言 HTTP 400 + code≠0 + message 含校验提示
      2. 登录缺 username → 400
    Expected Result: 两次 400
    Evidence: .sisyphus/evidence/task-9-validation-400.txt
  ```

  **Commit**: YES
  - Message: `feat(auth): register/login endpoints with validation`
  - Files: auth/controller/**、auth/dto/**
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 10. AuthInterceptor + UserContext + ADMIN 检查

  **What to do**:
  - `auth/interceptor/UserContext`：ThreadLocal<CurrentUser>（id/username/role）；静态 set/get/clear
  - `auth/interceptor/AuthInterceptor implements HandlerInterceptor`：
    - preHandle：OPTIONS 请求直接放行（CORS 预检）；`Authorization: Bearer <token>` 缺失/格式错 → 401 ApiResponse（写 response JSON 后 false）
    - JwtService.verify 失败 → 401（区分过期/无效文案）
    - 解析 userId → UserService.getEnabledUserById：null 或 enabled=0 → 401 "用户已被禁用"
    - UserContext.set(currentUser)
    - 路径 `/api/admin/**` 且 role≠ADMIN → 403
    - afterCompletion：**必须 UserContext.clear()**
  - `config/WebMvcConfig implements WebMvcConfigurer`：注册拦截器 addPathPatterns("/api/**")，excludePathPatterns("/api/auth/register","/api/auth/login","/api/health","/actuator/**")
  - 集成测试（@SpringBootTest + MockMvc 或真实 curl）：
    - 无 token 访问 /api/files → 401
    - 伪造/过期 token → 401
    - 合法 USER token 访问 /api/admin/users → 403
    - 合法 ADMIN token 访问 → 放行（用 V2 种子 admin 登录获取）
    - OPTIONS /api/xxx → 放行（非 401）
    - ThreadLocal 清理：请求后 UserContext.get()==null（可通过过滤器顺序测试或代码审查断言）
  - Token 不出现在任何日志（log 中只记 userId）

  **Must NOT do**:
  - 不把 Token 放 URL 校验（只认 header）
  - 不实现 Refresh Token/黑名单（明确 out of scope）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 全部受保护端点的安全闸门，错误语义影响全项目
  - **Skills**: [`java-dev`]
    - `java-dev`: 拦截器/ThreadLocal 清理规范
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖 T8；与 T9 可并行开发但提交需在 T9 后——实际上 T9/T10 互不依赖文件，可并行）
  - **Parallel Group**: Wave 2 第二步（与 T9 并行）
  - **Blocks**: T11, T33
  - **Blocked By**: T7, T8, T4

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:373-387` — 7.4 REST Token 拦截器完整规则（排除清单/ADMIN/ThreadLocal）
  - `doc/聊天室系统-后端OpenCode实施文档.md:675-685` — 16.1 伪造/过期/禁用测试

  **Acceptance Criteria**:
  - [ ] 6 类集成测试（401×3、403、放行、OPTIONS）通过
  - [ ] 拦截器排除清单与文档一致

  **QA Scenarios**:

  ```
  Scenario: 认证保护矩阵（happy + edge）
    Tool: Bash (curl)
    Preconditions: 应用运行；admin 种子账号（admin/admin123456）；已注册 qauser1
    Steps:
      1. 无 header `curl.exe -s http://localhost:8080/api/files?scope=PUBLIC` → 断言 401
      2. `Authorization: Bearer garbage.token.value` → 401
      3. qauser1 登录取 token；用该 token 访问 `/api/admin/users` → 403
      4. admin 登录取 token；访问 `/api/admin/users` → 200 code=0（此时端点可能 404——只要不是 401/403 即算拦截器放行；T33 实现前可用任意 /api/admin/** 探测路径，断言非 401/403）
      5. `curl.exe -s -X OPTIONS http://localhost:8080/api/files -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: GET"` → 非 401
    Expected Result: 1-3 均 401/403；4 放行；5 放行
    Failure Indicators: 任何一步状态码错位
    Evidence: .sisyphus/evidence/task-10-auth-matrix.txt（含每步响应体）

  Scenario: 过期 Token 专属文案（edge）
    Tool: Bash (JUnit)
    Steps:
      1. 测试内用 JwtService 以过期时间构造 token → 断言 401 且 message 含 "过期"
    Expected Result: 文案断言通过
    Evidence: .sisyphus/evidence/task-10-expired-message.txt
  ```

  **Commit**: YES
  - Message: `feat(auth): bearer token interceptor with UserContext and admin guard`
  - Files: auth/interceptor/**、config/WebMvcConfig.java
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 11. B1 阶段验收：认证全链路

  **What to do**:
  - 验证文档 B1 验收标准原文：
    1. 数据库无明文密码（全表 password_hash 匹配 $2 开头）
    2. 正确密码成功 / 错误密码失败（已由 T7/T9 覆盖，此处复跑）
    3. 过期/伪造 token 401（T10 复跑）
    4. **pom 无 spring-boot-starter-security**（dependency:tree 复查）
  - 完整流程 e2e：admin 登录 → 受保护端点放行；user 禁用后（手工 SQL UPDATE enabled=0）token 立即失效 → 401（再改回）
  - `mvnw.cmd clean test package` 全绿
  - 输出 B1 完成清单 + 剩余任务

  **Must NOT do**:
  - 不为验收篡改数据库 admin 密码哈希（禁用测试用 qauser 做完恢复）

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 验证型任务
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 2 收尾
  - **Blocks**: T12-T34
  - **Blocked By**: T9, T10

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:643-647` — B1 验收标准原文

  **Acceptance Criteria**:
  - [ ] 上述 4 项验证全部通过并留证
  - [ ] 构建全绿

  **QA Scenarios**:

  ```
  Scenario: B1 验收四连（happy path）
    Tool: Bash (mysql + curl + mvnw)
    Steps:
      1. `mysql -uchatroom -pchatroom123 chatroom_lab -e "SELECT COUNT(*) FROM users WHERE password_hash NOT LIKE '\$2%';"` → 0
      2. `mvnw.cmd dependency:tree | Select-String security-starter` → 空
      3. `mvnw.cmd clean test package` → BUILD SUCCESS
      4. 禁用-恢复 e2e（qauser）：禁用后旧 token 访问 → 401；恢复后重新登录 → 200
    Expected Result: 4/4
    Evidence: .sisyphus/evidence/task-11-b1-acceptance.txt
  ```

  **Commit**: YES（如产生修复）
  - Message: `test(b1): phase acceptance`
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 12. chat_messages 实体 + Mapper XML 分页 + ChatMessageService

  **What to do**:
  - `chat/entity/ChatMessageEntity`（@TableName("chat_messages")）：id/senderId/receiverId/chatType/content/textColor/sentAt；@TableField 映射驼峰
  - `chat/mapper/ChatMessageMapper extends BaseMapper<ChatMessageEntity>` + `resources/mapper/ChatMessageMapper.xml`：
    - `selectHistoryPage`：参数 (userId, peerId, chatType, page, size)；私聊双向（(sender=a AND receiver=b) OR (sender=b AND receiver=a)）；广播 chat_type=BROADCAST；按 sent_at DESC 分页（**复杂 SQL 写 XML、全部 #{}**）
    - 统计聚合可后续按需（本任务只做历史分页 + 简单 count）
  - `chat/service/ChatMessageService`：`saveMessage(senderId, receiverId, chatType, content, textColor)`（先入库拿 id）；`queryHistory(...)`（走 XML + MyBatis-Plus 分页 Page 对象，分页拦截器在 T5 已注册）
  - 测试：插入 25 条私聊消息 → 分页 size=10 第 2 页恰好 10 条且时间倒序；广播查询只含 BROADCAST；跨用户私聊隔离（A-B 查询不含 A-C 消息）

  **Must NOT do**:
  - 不用 ${}（哪怕排序字段）
  - WebSocket Handler 后续不得直接调 Mapper（通过本 Service）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: XML 分页 SQL + 双向私聊语义，需仔细
  - **Skills**: [`java-dev`]
    - `java-dev`: MyBatis XML 与分页规范
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T13/T14 并行）
  - **Parallel Group**: Wave 3
  - **Blocks**: T16, T17
  - **Blocked By**: T5, T3（chat_messages 表）

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:270-272` — chat_messages 字段语义（receiver_id 群聊为空、chat_type 枚举）
  - `doc/聊天室系统-后端OpenCode实施文档.md:291-301` — 5.2 节（分页拦截器/复杂 SQL 入 XML）
  - `doc/聊天室系统-后端OpenCode实施文档.md:687-691` — 16.2 消息历史分页测试

  **Acceptance Criteria**:
  - [ ] XML 分页查询测试通过（页大小/顺序/隔离）
  - [ ] Page 对象 total 正确

  **QA Scenarios**:

  ```
  Scenario: 历史分页（happy path）
    Tool: Bash (JUnit)
    Steps:
      1. `mvnw.cmd test -Dtest=ChatMessageServiceTest`
      2. 断言：25 条 → Page(2,10).records.size()==10，total==25，records[0].sentAt >= records[9].sentAt
    Expected Result: 全绿
    Evidence: .sisyphus/evidence/task-12-history-pagination.txt

  Scenario: 私聊隔离（edge）
    Tool: Bash (JUnit)
    Steps:
      1. 插入 A→B、A→C 各 5 条；查询 history(A,B) → 恰 10 条（双向 5+5）且 content 无 C 专属标记
    Expected Result: 隔离正确
    Evidence: .sisyphus/evidence/task-12-private-isolation.txt
  ```

  **Commit**: YES
  - Message: `feat(chat): message entity + paginated history XML + service`
  - Files: chat/entity/**、chat/mapper/**、resources/mapper/ChatMessageMapper.xml、chat/service/**
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 13. WS 协议信封 + MessageType + 编解码

  **What to do**:
  - `chat/protocol/MessageType` 枚举：AUTH、AUTH_SUCCESS、AUTH_FAILURE、PING、PONG、USER_LIST、USER_ONLINE、USER_OFFLINE、CHAT_PRIVATE、CHAT_BROADCAST、CHAT_MESSAGE、RTC_OFFER、RTC_ANSWER、RTC_ICE、SERVER_NOTICE、ERROR（**16 种，与文档 8.2 一字不差**）
  - `chat/protocol/MessageEnvelope`：type(MessageType)、requestId(String)、timestamp(long)、payload(JsonNode 或 Map<String,Object>)；Jackson 注解保证字段名精确
  - `chat/protocol/EnvelopeCodec`：encode(MessageEnvelope)→String（Jackson ObjectMapper 注入）、decode(String)→MessageEnvelope（**未知 type → 返回 ERROR 或抛受控异常**，不 crash）；空/畸形 JSON → 受控异常
  - 消息大小限制：读取 `chatroom.ws.max-text-message-size`，后续在 Handler 注册时生效
  - 单元测试：往返编解码（全部 16 种 type 各 1 例）；未知 type（"XXX_NOPE"）→ 受控失败；畸形 JSON；缺 requestId/timestamp 的宽容性（requestId 允许 null？文档要求携带——编解码层不强制，业务层校验）

  **Must NOT do**:
  - 不引入 STOMP（raw WebSocket）
  - 字段名不增不改（前端契约）

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 纯数据结构 + 编解码工具，模式清晰
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T12/T14 并行）
  - **Parallel Group**: Wave 3
  - **Blocks**: T15, T16, T32
  - **Blocked By**: T1

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:417-429` — 8.2 消息信封 + 全部消息类型清单
  - 前端契约：room-chat draft（AUTH 首帧结构 `{type:"AUTH",requestId,timestamp,payload:{token}}`）

  **Acceptance Criteria**:
  - [ ] 16 种枚举与文档一致
  - [ ] 往返/畸形输入测试通过

  **QA Scenarios**:

  ```
  Scenario: 往返编解码（happy path）
    Tool: Bash (JUnit)
    Steps:
      1. `mvnw.cmd test -Dtest=EnvelopeCodecTest`
      2. 对每个 MessageType：构造 envelope→encode→decode→字段全等
    Expected Result: 16 例全过
    Evidence: .sisyphus/evidence/task-13-envelope-roundtrip.txt

  Scenario: 畸形输入（edge）
    Tool: Bash (JUnit)
    Steps:
      1. decode("not json") → 受控异常（自定义 ProtocolException，非 NPE/IOException 裸抛）
      2. decode('{"type":"FOO"}') → 未知类型处理路径明确
    Expected Result: 两例断言通过
    Evidence: .sisyphus/evidence/task-13-malformed-input.txt
  ```

  **Commit**: YES
  - Message: `feat(chat): ws protocol envelope + 16 message types + codec`
  - Files: chat/protocol/**
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 14. SessionRegistry + PresenceService + 心跳

  **What to do**:
  - `chat/websocket/SessionHolder`：userId、原始 WebSocketSession、装饰后 session、lastActivityMillis、authenticated 标志
  - `chat/websocket/WebSocketSessionRegistry`：`ConcurrentHashMap<Long, SessionHolder>`
    - `register(userId, session)`：**同 userId 旧连接 close()（踢旧）后放入新**
    - `unregister(session)`、`get(userId)`、`onlineSnapshot()`（List<Long> 快照）
    - 发送统一出口 `send(userId, String text)`：用 `ConcurrentWebSocketSessionDecorator(session, 5000, 512*1024)` 串行化 + 限流（**同一 session 发送串行**）；目标不在线返回 false
    - `broadcast(String text, excludeUserId?)`：遍历快照，**单连接异常 catch 继续**（不中断广播），返回成功数
  - `chat/service/PresenceService`：
    - 上线：register + 广播 USER_ONLINE{userId,username}
    - 下线：unregister + 广播 USER_OFFLINE
    - 心跳：`touch(userId)` 更新 lastActivity；@Scheduled(fixedDelay 读配置 heartbeat-seconds) 扫描：`now - lastActivity > idle-timeout-seconds*1000` → 关闭连接 + USER_OFFLINE
    - 上线广播 USER_ONLINE 前先发 USER_LIST 给该用户（在线列表）
  - `config` 中 @EnableScheduling
  - 测试（时间参数走 test profile 短值）：踢旧（同 user 二连，旧 session isOpen()==false）；心跳超时清理（test: heartbeat=2s idle=4s，touch 后不清理，不 touch 5s 后清理）；广播异常隔离（一个 session close 后 broadcast 其余正常送达）

  **Must NOT do**:
  - 不用 synchronized 全局锁（ConcurrentHashMap + per-session 串行）
  - 不让广播循环因单连接异常中断

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 并发语义核心（文档 8.3 全部并发规则落点）
  - **Skills**: [`java-dev`]
    - `java-dev`: 并发与资源清理规范
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T12/T13 并行）
  - **Parallel Group**: Wave 3
  - **Blocks**: T15, T16, T17, T33
  - **Blocked By**: T1, T2（配置）

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:430-440` — 8.3 并发规则全表（踢旧/串行/快照广播/20-60s 心跳）
  - `doc/聊天室系统-后端OpenCode实施文档.md:696-701` — 16.3 WS 测试清单
  - Spring 文档 ConcurrentWebSocketSessionDecorator（发送串行化标准方案）

  **Acceptance Criteria**:
  - [ ] 踢旧/超时清理/广播隔离三类测试通过
  - [ ] 时间参数全部可配置（非硬编码）

  **QA Scenarios**:

  ```
  Scenario: 重复连接踢旧（happy path）
    Tool: Bash (JUnit + StandardWebSocketClient)
    Steps:
      1. 测试用 StandardWebSocketClient 连 /ws/chat 两次同一 userId（首帧 AUTH 模拟可在 T15 后补——本任务用直接 register 模拟）
      2. 断言第一 holder 的 session.isOpen()==false，registry.get(userId) 是第二 holder
    Expected Result: 断言通过
    Evidence: .sisyphus/evidence/task-14-kick-old-session.txt

  Scenario: 心跳超时清理（edge，用 test profile 短时限）
    Tool: Bash (JUnit)
    Steps:
      1. register 用户不 touch；等待 idle-timeout+1s → 断言被清理且 USER_OFFLINE 广播被调用（Mockito verify 或捕获）
      2. register 用户每 heartbeat touch → 断言仍在线
    Expected Result: 两种路径断言均过
    Evidence: .sisyphus/evidence/task-14-heartbeat-timeout.txt
  ```

  **Commit**: YES
  - Message: `feat(chat): session registry with kick-old + presence heartbeat`
  - Files: chat/websocket/**、chat/service/PresenceService.java
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 15. WsAuthService：首帧 AUTH（5s 窗口）

  **What to do**:
  - `chat/websocket/WebSocketAuthService`：
    - 连接建立后状态=UNAUTHENTICATED；**认证完成前只接受 AUTH 帧**（其他类型 → 回 ERROR{"reason":"未认证"}，可累计）
    - 收到 AUTH：payload.token → JwtService.verify（失败→AUTH_FAILURE{reason}+close）；UserService 复查 enabled（禁用→AUTH_FAILURE+close）
    - 成功：标记 authenticated → PresenceService 上线（含 USER_LIST 下发 + USER_ONLINE 广播）→ 回 AUTH_SUCCESS{userId,username,displayName}
    - **5 秒未发 AUTH**（`chatroom.ws.auth-timeout-seconds`，test=2s）：scheduler 扫描未认证超时连接 → close（可附带 AUTH_FAILURE{reason:"认证超时"}）
  - 集成测试（StandardWebSocketClient，test profile auth-timeout=2s）：
    - 正常首帧 AUTH → 收到 AUTH_SUCCESS + USER_LIST
    - 未认证先发 CHAT_PRIVATE → 收 ERROR；连接保持（或按实现关闭——断言明确行为）
    - 错 token → AUTH_FAILURE + 服务端 close（client onClose 触发）
    - 2s 无 AUTH → 服务端 close
    - 同一用户第二个连接认证 → 第一个连接 onClose（踢旧，联动 T14）

  **Must NOT do**:
  - 认证前不得转发任何业务消息
  - Token 不得记录进日志

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: WS 安全闸门 + 异步超时状态机
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖 T8/T13/T14）
  - **Parallel Group**: Wave 3 第二步
  - **Blocks**: T16
  - **Blocked By**: T8, T13, T14

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:389-401` — 7.5 WS Token 首帧 AUTH 完整规则（5 秒/AUTH_SUCCESS/AUTH_FAILURE）
  - `doc/聊天室系统-后端OpenCode实施文档.md:696-701` — 16.3 未发 AUTH 被拒测试

  **Acceptance Criteria**:
  - [ ] 5 类集成测试通过
  - [ ] 超时参数可配置

  **QA Scenarios**:

  ```
  Scenario: 首帧认证全矩阵（happy + edge）
    Tool: Bash (JUnit，StandardWebSocketClient)
    Steps:
      1. 起应用（test profile）；StandardWebSocketClient connect ws://localhost:8080/ws/chat
      2. 立即发 CHAT_PRIVATE → 断言收到 type=ERROR
      3. 发 AUTH（有效 token）→ 断言 AUTH_SUCCESS 且随后 USER_LIST 到达
      4. 另一连接发 AUTH（垃圾 token）→ 断言 AUTH_FAILURE 且 onClose 在 1s 内触发
      5. 第三连接静默 3s（timeout=2s）→ 断言 onClose
    Expected Result: 5 步全过
    Failure Indicators: 任一消息类型/时序不符
    Evidence: .sisyphus/evidence/task-15-ws-auth-matrix.txt

  Scenario: 真实 HTTP 层 WS 握手（happy path，供前端联调信心）
    Tool: Bash (curl 升级请求探测)
    Steps:
      1. `curl.exe -s -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" http://localhost:8080/ws/chat`
      2. 断言 HTTP/1.1 101（握手成功）
    Expected Result: 101 Switching Protocols
    Evidence: .sisyphus/evidence/task-15-ws-handshake.txt
  ```

  **Commit**: YES
  - Message: `feat(chat): websocket first-frame auth with 5s window`
  - Files: chat/websocket/WebSocketAuthService.java 及测试
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 16. ChatWebSocketHandler：路由/私聊/广播/完整装配

  **What to do**:
  - 完善 `config/WebSocketConfig`（T2 占位）：@EnableWebSocket + registry.addHandler(handler, "/ws/chat")；注册时配置容器 max text message buffer（chatroom.ws.max-text-message-size）
  - `chat/websocket/ChatWebSocketHandler extends TextWebSocketHandler`：
    - afterConnectionEstablished：创建 SessionHolder（UNAUTHENTICATED），纳入 AuthService 超时扫描
    - handleTextMessage：EnvelopeCodec.decode（超长由容器拒）→ 未认证则仅 AUTH（转发 WebSocketAuthService）→ 已认证按 type 路由：
      - PING → 回 PONG（同 requestId）+ touch
      - CHAT_PRIVATE：payload{receiverId/content/textColor?} → 目标在线？离线→ERROR{"reason":"目标离线"}（同 requestId）；在线→ChatMessageService.saveMessage（**入库成功后**）→ 给发送方回 CHAT_MESSAGE{messageId,sentAt,…} + 转发给接收方 CHAT_MESSAGE；入库失败→ERROR
      - CHAT_BROADCAST：入库（receiver_id=null）→ 广播给所有在线（含发送方确认 CHAT_MESSAGE）
      - RTC_OFFER/RTC_ANSWER/RTC_ICE → 转发 RtcSignalService（T32 前先实现透传接口，目标在线校验）——**本任务实现信令转发骨架**（校验在线+转发，不解析 payload）
      - 未知 type → ERROR
    - afterConnectionClosed：authenticated 时 PresenceService 下线；清理 registry
    - 传输异常 handleTransportError：记日志 + 清理
  - **P2P 消息（WebRTC DataChannel）不经此 Handler，不入库**（仅信令经 RTC_* 转发）
  - 集成测试（两客户端 StandardWebSocketClient，均完成 AUTH）：
    - A→B 私聊：B 收到 CHAT_MESSAGE（content 一致），A 收到带 messageId 的确认；DB 中 chat_messages 新增 1 行 chat_type=PRIVATE
    - 广播：A/B/C 三客户端均收到
    - 私聊目标离线：A 发给不在线 D → A 收 ERROR（requestId 回显）
    - 未认证发送 → ERROR（T15 已覆盖，回归）
    - PING→PONG 回显 requestId
    - 消息超长（>max-text-message-size）→ 连接被容器拒绝或 ERROR，服务端不崩溃

  **Must NOT do**:
  - Handler 不直接执行 SQL（经 Service）
  - 广播单点异常不中断（T14 保障，此处集成验证）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: B2 核心，消息路由与持久化时序（先入库后确认）
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 3 第三步
  - **Blocks**: T17, T32, T33
  - **Blocked By**: T12, T13, T14, T15

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:405-441` — 8 节全（组件/信封/并发规则）
  - `doc/聊天室系统-后端OpenCode实施文档.md:696-701` — 16.3 WS 测试清单（私聊只到目标/广播全员/踢旧/心跳）

  **Acceptance Criteria**:
  - [ ] 6 类集成测试通过
  - [ ] 私聊确认消息带 DB messageId（入库先行）

  **QA Scenarios**:

  ```
  Scenario: 双客户端私聊 e2e（happy path）
    Tool: Bash (JUnit 起服务 + StandardWebSocketClient ×2)
    Steps:
      1. qauser1/qauser2 各自 AUTH
      2. user1 发 {"type":"CHAT_PRIVATE","requestId":"r1","payload":{"receiverId":<uid2>,"content":"hi"}}
      3. 断言 user2 收 CHAT_MESSAGE content=="hi"；user1 收 CHAT_MESSAGE 含 messageId>0
      4. 断言 DB：SELECT COUNT(*) FROM chat_messages WHERE sender_id=<uid1> AND receiver_id=<uid2> AND chat_type='PRIVATE' 增加 1
    Expected Result: 全部断言通过
    Evidence: .sisyphus/evidence/task-16-private-chat-e2e.txt

  Scenario: 私聊离线目标（edge）
    Tool: Bash (JUnit)
    Steps:
      1. user1 在线，user3 不在线（已注册）
      2. user1 发 CHAT_PRIVATE 给 user3 → 断言 user1 收 ERROR 且 requestId=="r2"
      3. 断言 DB 无该消息行
    Expected Result: 明确错误+零入库
    Evidence: .sisyphus/evidence/task-16-offline-target-error.txt
  ```

  **Commit**: YES
  - Message: `feat(chat): chat websocket handler routing private/broadcast with persistence`
  - Files: config/WebSocketConfig.java、chat/websocket/ChatWebSocketHandler.java
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 17. ServerNoticeService + B2 验收 e2e

  **What to do**:
  - `chat/service/ServerNoticeService`：`broadcastNotice(String title, String content)` → registry.broadcast(SERVER_NOTICE envelope)；`sendNotice(userId, …)` 单发；供 T33 admin 复用
  - ERROR 统一格式复核：全部错误回包 `{"type":"ERROR","requestId":<回显>,"payload":{"reason":…}}`
  - B2 验收（文档原文）：两账号私聊和广播（T16）；未认证连接不能发消息（T15/T16）；断线会被清理（关闭 client → 60s/test 短时内 USER_OFFLINE 广播 + registry 清空）
  - `mvnw.cmd clean test package` 全绿；输出 B2 完成清单

  **Must NOT do**:
  - 不实现 RTC 信令完整逻辑（T32）——本任务只保证转发骨架编译通过

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: e2e 验收 + 补齐通知服务，需要多客户端时序测试执行
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 3 收尾
  - **Blocks**: T24 之后无（B3 可与 B2 收尾并行开始——实际 Wave 4 在 T17 后启动）
  - **Blocked By**: T16

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:649-653` — B2 验收标准原文
  - `doc/聊天室系统-后端OpenCode实施文档.md:696-701` — 16.3 清单

  **Acceptance Criteria**:
  - [ ] 断线清理测试（USER_OFFLINE 广播）
  - [ ] 构建全绿

  **QA Scenarios**:

  ```
  Scenario: 断线清理（edge）
    Tool: Bash (JUnit，test profile idle-timeout=4s)
    Steps:
      1. user1/user2 AUTH；user2 客户端直接 close
      2. 断言 user1 在 6s 内收到 USER_OFFLINE{userId:<uid2>}
      3. registry.get(uid2)==null
    Expected Result: 全过
    Evidence: .sisyphus/evidence/task-17-disconnect-cleanup.txt

  Scenario: 服务器通知广播（happy path）
    Tool: Bash (JUnit)
    Steps:
      1. 两客户端在线；调用 ServerNoticeService.broadcastNotice("t","c")
      2. 两端均收到 SERVER_NOTICE payload.title=="t"
    Expected Result: 全过
    Evidence: .sisyphus/evidence/task-17-server-notice.txt
  ```

  **Commit**: YES
  - Message: `feat(chat): server notice service + b2 acceptance`
  - Files: chat/service/ServerNoticeService.java 及测试
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 18. file 模块：三实体 + Mapper + 条件查询 XML

  **What to do**:
  - `file/entity/`：`StoredFileEntity`（stored_files：id/ownerId/scope/originalName/storedName/relativePath/sizeBytes/sha256/createdAt）、`UploadSessionEntity`（upload_sessions：id/uploadId 唯一/ownerId/scope/fileName/fileSize/chunkSize/totalChunks/fileSha256/status[INIT/COMPLETED/EXPIRED]/expireAt/createdAt）、`UploadChunkEntity`（upload_chunks：id/uploadId/chunkIndex/chunkSize/chunkSha256/createdAt）
  - `file/mapper/`：三个 BaseMapper + `resources/mapper/StoredFileMapper.xml`：
    - `selectFilesPage`（scope、ownerId、可选关键词 like original_name——**like 值用 #{} 参数 + CONCAT('%',#{kw},'%')**、分页、created_at DESC）
    - 缺块查询：`selectExistingChunkIndexes(uploadId)` 返回 List<Integer>
  - `file/service/FileQueryService`：列表（PUBLIC → scope=PUBLIC；PRIVATE → scope=PRIVATE AND owner_id=当前用户——**授权只信 UserContext，不收客户端 ownerId**）
  - 测试：分页/过滤/缺块查询；insert 唯一索引冲突（同 uploadId+chunkIndex 二次 insert → DuplicateKeyException 捕获路径）

  **Must NOT do**:
  - like 拼接不用字符串拼接（防注入）

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T19 并行）
  - **Parallel Group**: Wave 4
  - **Blocks**: T20, T23
  - **Blocked By**: T5, T3

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:274-289` — 5.1 stored_files/upload_sessions/upload_chunks 字段
  - `doc/聊天室系统-后端OpenCode实施文档.md:687-691` — 16.2 条件查询测试

  **Acceptance Criteria**:
  - [ ] 三实体字段与表一一对应
  - [ ] 列表查询按 scope+owner 隔离

  **QA Scenarios**:

  ```
  Scenario: 文件列表隔离（happy path）
    Tool: Bash (JUnit)
    Steps:
      1. 造数据：user1 私人 2 个、公共 3 个、user2 私人 1 个
      2. FileQueryService.list(user1, PRIVATE) → 恰 2；list(user1, PUBLIC) → 3；list(user2, PRIVATE) → 1
    Expected Result: 计数断言全过
    Evidence: .sisyphus/evidence/task-18-file-list-isolation.txt

  Scenario: 唯一索引幂等基础（edge）
    Tool: Bash (JUnit)
    Steps:
      1. 同 (uploadId, chunkIndex) 插两次 → 断言第二次抛 DuplicateKeyException（可被上层捕获转幂等）
    Expected Result: 异常类型断言通过
    Evidence: .sisyphus/evidence/task-18-unique-index.txt
  ```

  **Commit**: YES
  - Message: `feat(file): entities + mappers + conditional query xml`
  - Files: file/entity/**、file/mapper/**、resources/mapper/StoredFileMapper.xml
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 19. StorageService：路径安全 + 流式工具

  **What to do**:
  - `file/storage/StorageService`：
    - `resolve(String relativePath)`：`storageRoot.resolve(relativePath).normalize()`；**`!startsWith(storageRoot.normalize())` → 抛 PathTraversalException（403/400）**；返回绝对 Path
    - `randomStoredName(String originalName)`：UUID/随机 + 保留原扩展（扩展名白名单可选）
    - `ensureUserDir(userId)`、`ensurePublicDir()`、`ensureTempDir(uploadId)`
    - `copyStream(InputStream, OutputStream)` 流式复制缓冲 64KiB（**禁止 readAllBytes**）
    - `sha256(Path)`：DigestUtils（spring-security-crypto 或手写 MessageDigest 流式摘要）
    - `atomicMove(from, to)`：Files.move(ATOMIC_MOVE, 若不支持退回 REPLACE_EXISTING 并 log warn）
    - `deleteQuietly(Path)`：删除文件（目录递归删除用于清理）
  - 测试：路径穿越（"../etc/passwd"、绝对路径注入、"a/../../b"）全部拒绝；正常相对路径解析成功；sha256 与已知值一致；原子移动成功

  **Must NOT do**:
  - 不信任任何客户端路径输入（全部经 normalize+startsWith 校验）
  - 不整块读文件进内存

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 安全边界核心（文档 17 章路径穿越防护）
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T18 并行）
  - **Parallel Group**: Wave 4
  - **Blocks**: T21, T22, T23
  - **Blocked By**: T2（storage-root 配置）

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:445-462` — 9.1 存储目录与规则（normalize/随机名/相对路径）
  - `doc/聊天室系统-后端OpenCode实施文档.md:704-712` — 16.4 路径穿越被拒测试

  **Acceptance Criteria**:
  - [ ] 穿越 3 变体全拒 + 正常放行
  - [ ] sha256 流式实现与工具值一致

  **QA Scenarios**:

  ```
  Scenario: 路径穿越矩阵（edge 安全）
    Tool: Bash (JUnit)
    Steps:
      1. resolve("../outside.txt") → PathTraversalException
      2. resolve("users/1/../../storage-x") → PathTraversalException
      3. resolve("C:\\Windows\\system32") / 绝对路径变体 → 拒绝
      4. resolve("users/42/file.bin") → 正常返回 storageRoot 下路径
    Expected Result: 3 拒 1 允
    Evidence: .sisyphus/evidence/task-19-path-traversal.txt

  Scenario: SHA-256 流式正确性（happy path）
    Tool: Bash (JUnit)
    Steps:
      1. 生成 10MiB 随机临时文件；StorageService.sha256 == Files.hash 等价参考（java.security 手动或已知向量 "abc"→ba7816bf…）
    Expected Result: 摘要一致
    Evidence: .sisyphus/evidence/task-19-sha256-stream.txt
  ```

  **Commit**: YES
  - Message: `feat(file): storage service with traversal guard and streaming utils`
  - Files: file/storage/**
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 20. 上传会话 API + 缺块查询

  **What to do**:
  - `file/controller/FileController`：
    - `POST /api/uploads`（body：{fileName, fileSize, scope, chunkSize?, fileSha256}）：校验 scope ∈ {PUBLIC, PRIVATE}、fileSize>0 ≤2GB、chunkSize 默认 2MiB；计算 totalChunks=ceil(fileSize/chunkSize)；生成 uploadId（UUID）；insert upload_sessions status=INIT、expireAt=now+24h；创建 temp/{uploadId} 目录；响应 {uploadId, chunkSize, totalChunks}
    - `GET /api/uploads/{uploadId}`：校验归属（owner==当前用户）；返回 {status, totalChunks, missingIndexes: List<Integer>}（0..totalChunks-1 中减去已存在索引）
    - `GET /api/files?scope=PUBLIC|PRIVATE`：FileQueryService 列表（分页 page/size 参数）
  - `file/service/UploadSessionService`
  - 测试：创建会话（断言 totalChunks 计算：3MiB/2MiB=2；2MiB 整=1）；他人 uploadId 查询 → 403；缺块列表正确（预插 0、2 块 → missing=[1,3..]）；scope 非法 → 400

  **Must NOT do**:
  - Controller 不写业务（薄层）
  - 不接受客户端指定 uploadId

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖 T18；可与 T19 之后并行）
  - **Parallel Group**: Wave 4 第二步
  - **Blocks**: T21, T24
  - **Blocked By**: T18, T4, T10（拦截器保护 /api/**）

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:464-476` — 9.2 API 表（POST /api/uploads、GET /api/uploads/{uploadId}、GET /api/files）
  - 前端契约：room-chat draft（并发 3 块上传、断点续传需要 missing 接口）

  **Acceptance Criteria**:
  - [ ] 会话创建/缺块/列表三组测试通过
  - [ ] 归属校验（403）通过

  **QA Scenarios**:

  ```
  Scenario: 创建上传会话（happy path）
    Tool: Bash (curl)
    Preconditions: 应用运行；qauser1 token
    Steps:
      1. `curl.exe -s -X POST http://localhost:8080/api/uploads -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{"fileName":"test.bin","fileSize":3145728,"scope":"PRIVATE","fileSha256":"..."}'`
      2. 断言 code=0 且 data.totalChunks==2 且 data.uploadId 非空
      3. `curl.exe -s "http://localhost:8080/api/uploads/<uploadId>" -H "Authorization: Bearer <token>"` → missingIndexes==[0,1]
    Expected Result: 往返正确
    Evidence: .sisyphus/evidence/task-20-create-session.txt

  Scenario: 越权访问会话（edge）
    Tool: Bash (curl)
    Steps:
      1. qauser2 token 访问 qauser1 的 uploadId → 断言 403
    Expected Result: 403
    Evidence: .sisyphus/evidence/task-20-foreign-session-403.txt
  ```

  **Commit**: YES
  - Message: `feat(file): upload session api with missing-chunk query`
  - Files: file/controller/FileController.java、file/service/UploadSessionService.java
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 21. 分块上传：SHA-256 校验 + 差错模拟 + 幂等

  **What to do**:
  - `PUT /api/uploads/{uploadId}/chunks/{index}`（body=原始字节，header `X-Chunk-SHA256`）：
    1. 会话归属+status=INIT 校验（否则 403/409）；index ∈ [0, totalChunks)（否则 400）
    2. **差错模拟**：`file/service/ErrorSimulator`——先按概率（RuntimeSettingsService 当前值，初始=application.yml 0.20）模拟丢失：**返回 500/503 类可重试错误且不落盘**；**固定种子**（`chatroom.error-simulation-seed`，非空时 `new Random(seed)` 决定性序列）→ 测试可复现
    3. 通过模拟后：读 body（**流式**，chunk ≤ 2MiB 可缓冲）计算 SHA-256；与 header 不一致 → **422 "分块摘要不匹配"**（不保存）
    4. 一致 → 写 `temp/{uploadId}/{index}.part` + insert upload_chunks
    5. **幂等**：同 (uploadId,index) 再传：若已有记录且摘要一致 → 直接 200 成功（不报错）；唯一索引冲突路径捕获 DuplicateKeyException 转幂等响应
  - `RuntimeSettingsService`（本任务最小实现：get/setErrorRate，@Bean 内存态，初始读配置；T33 扩展 admin API）
  - 测试：固定种子 + rate=0.20 → 前若干块确定失败（可预知序列），重传同块最终成功；坏摘要 → 422 且无 .part 文件；重复同块 → 幂等 200；他人会话 → 403；越界 index → 400

  **Must NOT do**:
  - 差错模拟不得伪造"成功"（只模拟丢块失败）
  - 不得跳过摘要校验直接落盘

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 课程项目核心实验特性（差错+重传+幂等），逻辑分支多
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 4 第三步
  - **Blocks**: T22, T33
  - **Blocked By**: T19, T20

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:478-489` — 9.3 分块与差错重传全规则（模拟丢失→422→幂等→固定种子）
  - `doc/聊天室系统-后端OpenCode实施文档.md:704-712` — 16.4 分块摘要错误/重复幂等/20% 固定随机测试
  - 前端契约：单块重试 5 次退避——服务端 5xx 模拟丢失后前端重试，最终成功

  **Acceptance Criteria**:
  - [ ] 固定种子 20% 场景下自动化测试：全部块最终成功（重传后）
  - [ ] 422/幂等/403/400 四类行为断言

  **QA Scenarios**:

  ```
  Scenario: 固定种子差错下最终成功（happy path，文档 16.4 核心）
    Tool: Bash (JUnit：seed=42, rate=0.2)
    Steps:
      1. 创建 10 块会话；循环上传每块（失败即重传，最多 10 次）
      2. 断言：至少 1 块首次被模拟拒绝（证明模拟生效）；全部块最终 missing==[] 
      3. 断言：同种子重跑序列一致（确定性）
    Expected Result: 差错真实发生且重传收敛
    Evidence: .sisyphus/evidence/task-21-error-sim-retry.txt

  Scenario: 坏摘要拒绝（edge）
    Tool: Bash (JUnit/curl)
    Steps:
      1. PUT 块 body="hello" 但 X-Chunk-SHA256=<"world" 的摘要> → 422
      2. 断言 temp 目录无该 .part 且 upload_chunks 无记录
    Expected Result: 422 + 零残留
    Evidence: .sisyphus/evidence/task-21-bad-digest-422.txt

  Scenario: 重复上传同块幂等（edge）
    Tool: Bash (JUnit)
    Steps:
      1. 上传 index=0 成功；再次上传同块同摘要 → 200（幂等）
      2. 断言 upload_chunks 中 (uploadId,0) 仅 1 行
    Expected Result: 幂等成功且表内唯一
    Evidence: .sisyphus/evidence/task-21-idempotent-chunk.txt
  ```

  **Commit**: YES
  - Message: `feat(file): chunk upload with sha256, error simulation, idempotency`
  - Files: file/controller/**（PUT 端点）、file/service/ErrorSimulator.java、RuntimeSettingsService.java
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 22. 合并与注册：/complete + 原子可见

  **What to do**:
  - `POST /api/uploads/{uploadId}/complete`：
    1. 归属+INIT 校验
    2. 缺块 → **409 "分块不完整，缺少索引 […]"**
    3. 按序合并 temp/*.part → temp/{uploadId}.merged（**流式 64KiB 复制**）
    4. 整体 SHA-256 vs 会话 fileSha256：不一致 → **422 + 删除 merged + 会话保持 INIT（可续传）**
    5. 一致：随机 storedName；目标位置 scope=PUBLIC→storage/public、PRIVATE→storage/users/{ownerId}；**atomicMove**；insert stored_files（relative_path 只存相对）；更新会话 status=COMPLETED
    6. **失败路径绝不产生可见文件**（合并失败/摘要失败均清理）
  - 并发保护：complete 期间置 status=MERGING 或 synchronized(uploadId)（简单方案可接受——课程项目）
  - 测试：全块 complete → stored_files 1 行 + 物理文件就位 + temp 清理；缺块 → 409；摘要错 → 422 且无可见文件且会话仍 INIT；文件真实内容逐字节等于源

  **Must NOT do**:
  - 不在 complete 前暴露合并文件
  - 失败不删已传分块（可续传语义）

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 4 第四步
  - **Blocks**: T24
  - **Blocked By**: T21

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:484-487` — complete 流程（检查/顺序合并/摘要/原子移动/失败不可见）
  - `doc/聊天室系统-后端OpenCode实施文档.md:704-712` — 16.4 缺块不能 complete/完整摘要一致测试

  **Acceptance Criteria**:
  - [ ] 4 类路径测试（成功/409/422/内容一致）通过
  - [ ] 成功后 temp/{uploadId} 目录清空

  **QA Scenarios**:

  ```
  Scenario: 完整合并（happy path）
    Tool: Bash (JUnit)
    Steps:
      1. 生成 5MiB 随机文件；分 3 块（2MiB/2MiB/1MiB）全传成功（关差错或 seed 环境下）
      2. complete → 200
      3. 断言 stored_files 新行 scope/size/sha256 正确；物理文件字节数==5MiB；temp 目录空
      4. 重新读回合并文件与源逐字节比较（Arrays.equals 分段）
    Expected Result: 内容完整一致
    Evidence: .sisyphus/evidence/task-22-complete-merge.txt

  Scenario: 缺块 409（edge）
    Tool: Bash (JUnit)
    Steps:
      1. 只传 0/2 两块（total=3）→ complete → 409 且 message 含 "1"（缺失索引）
    Expected Result: 409
    Evidence: .sisyphus/evidence/task-22-incomplete-409.txt
  ```

  **Commit**: YES
  - Message: `feat(file): complete endpoint with ordered merge and atomic registration`
  - Files: file/service/UploadSessionService.java（complete 逻辑）
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 23. Range 下载 + 删除 + 文件列表完善

  **What to do**:
  - `GET /api/files/{fileId}/download`：
    1. 查 stored_files：不存在 → 404
    2. **授权只按服务端记录**：PRIVATE 且 owner≠当前用户 → 403（ADMIN 可放行或同 403——按文档"访问他人私人文件"403，admin 例外不强制；选 403 对非 owner 一律，包括 admin？文档 6 节说 403=访问他人私人文件或角色不符；管理端无下载需求 → owner-only，但公共文件任何登录用户可下载）
    3. 无 Range → 200 + `Accept-Ranges: bytes` + Content-Length + `Content-Disposition: attachment; filename*=UTF-8''<encoded original_name>`（中文文件名 RFC 5987）
    4. `Range: bytes=start-end|start-|-suffix` → **206 + Content-Range: bytes s-e/total**；非法/越界 → **416 + Content-Range: bytes */total**
    5. **流式输出**（InputStreamResource/StreamingResponseBody，64KiB 缓冲；不得 readAllBytes）
  - `DELETE /api/files/{fileId}`：owner 或 ADMIN；删物理文件+DB 行；404/403 语义同上
  - `GET /api/files?scope=...&page=&size=` 完善（T20 骨架）
  - 测试：普通 200（长度正确）；Range 单段/多段之一（206，字节区间精确）；`bytes=-100` 后缀；`bytes=99999999-` 超界 → 416；文件不存在 404；user2 下载 user1 私人 → 403；user2 删 user1 私人 → 403；下载不中断 WS 连接（可省——语义上无关联）

  **Must NOT do**:
  - 不信任客户端 ownerId 参数（授权仅凭 DB + UserContext）
  - 大文件不进内存（QA 用 100MiB 验证内存不爆：jstat 或简化为流式实现审查+50MiB 冒烟）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: HTTP Range 语义细节多（206/416/Content-Range 精确性）
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T21/T22 并行——只依赖 T18/T19）
  - **Parallel Group**: Wave 4
  - **Blocks**: T24
  - **Blocked By**: T18, T19, T4

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:491-500` — 9.4 下载规则（200/206/Accept-Ranges/Content-Range/Content-Disposition/404/授权）
  - `doc/聊天室系统-后端OpenCode实施文档.md:704-712` — 16.4 Range 边界/用户隔离测试

  **Acceptance Criteria**:
  - [ ] 206/416/404/403/200 全矩阵测试
  - [ ] 中文文件名下载头正确

  **QA Scenarios**:

  ```
  Scenario: Range 下载矩阵（happy + edge）
    Tool: Bash (curl)
    Preconditions: 已上传 1MiB 测试文件并 complete
    Steps:
      1. `curl.exe -s -o full.bin http://localhost:8080/api/files/<id>/download -H "Authorization: Bearer <t>"` → 200 且字节数=1048576
      2. `curl.exe -s -o part.bin -H "Range: bytes=0-1023" ...` → 206 且 part 字节数=1024，内容==full 前 1024 字节
      3. `-H "Range: bytes=-256"` → 206 后 256 字节
      4. `-H "Range: bytes=999999999-"` → 416
      5. 响应头断言 Accept-Ranges: bytes / Content-Disposition 含 filename
    Expected Result: 5 步全过
    Evidence: .sisyphus/evidence/task-23-range-matrix.txt（含 -i 响应头输出）

  Scenario: 私人文件越权（edge）
    Tool: Bash (curl)
    Steps:
      1. user2 token 下载 user1 私人文件 → 403
      2. user2 token 删除 user1 私人文件 → 403（文件仍在）
      3. user1 下载自己 → 200；user2 下载 PUBLIC → 200
    Expected Result: 授权矩阵正确
    Evidence: .sisyphus/evidence/task-23-authz-isolation.txt

  Scenario: 大文件流式下载（内存安全）
    Tool: Bash (PowerShell)
    Steps:
      1. 上传并 complete 一个 ≥64MiB 文件（可用测试生成）
      2. 下载全程监控进程内存（Get-Process java | Select WorkingSet 采样）峰值 < 堆上限+256MiB 余量（启发式断言：不出现 OOM、下载字节完整）
    Expected Result: 无 OOM，字节数一致
    Evidence: .sisyphus/evidence/task-23-streaming-memory.txt
  ```

  **Commit**: YES
  - Message: `feat(file): range download + delete + list endpoints`
  - Files: file/controller/FileController.java
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 24. 过期清理任务 + B3 验收

  **What to do**:
  - `file/service/UploadCleanupTask`：@Scheduled(cron 每 30 分钟或 fixedDelay)：expireAt < now 且 status≠COMPLETED 的会话 → 删 temp/{uploadId} 目录 + 状态 EXPIRED（或删除行——选 EXPIRED 保留审计）
  - B3 验收（文档原文）：
    1. 20% 固定随机差错下最终摘要相同（T21/T22 复跑）
    2. 用户 A 不能访问用户 B 私人文件（T20/T23 复跑）
    3. **100MiB 文件不整块进内存**（T23 内存冒烟 + 代码扫描无 readAllBytes）
  - `mvnw.cmd clean test package` 全绿；输出 B3 完成清单

  **Must NOT do**:
  - 清理任务不得删 COMPLETED 会话/已注册文件

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 定时清理逻辑简单 + 验收复跑
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 4 收尾
  - **Blocks**: T34
  - **Blocked By**: T20, T22, T23

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:487` — 定时清理过期上传
  - `doc/聊天室系统-后端OpenCode实施文档.md:655-659` — B3 验收标准原文

  **Acceptance Criteria**:
  - [ ] 过期会话清理测试（造 expireAt 过去的会话 → 手动触发 → 目录删除+状态 EXPIRED）
  - [ ] B3 三项验收留证
  - [ ] 构建全绿

  **QA Scenarios**:

  ```
  Scenario: 过期清理（happy path）
    Tool: Bash (JUnit，直接调用 cleanup 方法)
    Steps:
      1. 造会话 expireAt=now-1h status=INIT + temp 目录含 2 个 .part
      2. 调 UploadCleanupTask.cleanupNow() → 断言目录删除、status=EXPIRED
      3. 造 COMPLETED 会话 → cleanup 后仍 COMPLETED 且文件在
    Expected Result: 清理正确且不误伤
    Evidence: .sisyphus/evidence/task-24-cleanup.txt

  Scenario: readAllBytes 静态扫描（guardrail）
    Tool: Bash (Grep 工具)
    Steps:
      1. Grep 工具搜索 pattern="readAllBytes" path="src/main/java" → 零匹配
    Expected Result: 零匹配（流式实现）
    Evidence: .sisyphus/evidence/task-24-no-readallbytes.txt
  ```

  **Commit**: YES
  - Message: `feat(file): expired upload cleanup + b3 acceptance`
  - Files: file/service/UploadCleanupTask.java
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 25. 二进制协议编解码 + Netty TCP/UDP codec

  **What to do**:
  - `network/protocol/ProbeFrame`（POJO）：magic(int=0x43484154)、version(byte=1)、type(byte：1=PING 2=PONG 3=DATA 4=ACK 5=END)、requestId(long)、sentAtNs(long)、payload(byte[])
  - `network/protocol/FrameEncoder`（ByteBuf 写出，**Big Endian**）+ `FrameDecoder`：
    - TCP 侧：Netty `LengthFieldBasedFrameDecoder`（lengthFieldOffset=22、lengthFieldLength=4、lengthAdjustment=0、initialBytesToStrip=0、maxFrameLength 合理上限如 2MiB+26）+ 自定义字节解码；**处理粘包/半包**
    - UDP 侧：`DatagramPacket` → 解码；**整报文 ≤1200 字节**（帧头 26 + payload ≤1174）
  - 校验：magic/version 不符 → 丢弃该帧（记计数，不抛异常炸通道）；长度异常只影响当前请求
  - 单元测试（**EmbeddedChannel**）：编码→解码往返字段全等；**粘包**：两帧连续写入一个 ByteBuf → 解出 2 帧；**半包**：一帧拆两半分两次写 → 拼出 1 帧；坏 magic → 丢弃且 channel 存活；UDP 超 1200 → 拒绝/丢弃

  **Must NOT do**:
  - 不用小端（必须 Big Endian）
  - 不引入额外序列化框架

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 二进制协议字节级精确 + Netty pipeline 语义（粘包/半包是验收重点）
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO（Wave 5 根）
  - **Parallel Group**: Wave 5 第一步
  - **Blocks**: T26, T27, T28, T29
  - **Blocked By**: T1（netty 依赖）, T5

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:505-519` — 10.1 二进制报文表（逐字节：magic/version/type/requestId/sentAtNs/payloadLength/payload）+ LengthFieldBasedFrameDecoder + UDP 1200
  - `doc/聊天室系统-后端OpenCode实施文档.md:714-720` — 16.5 TCP 编解码拆包/粘包测试

  **Acceptance Criteria**:
  - [ ] EmbeddedChannel 测试覆盖：往返/粘包/半包/坏 magic/UDP 长度
  - [ ] 帧头 26 字节（magic 4 + version 1 + type 1 + requestId 8 + sentAtNs 8 + payloadLength 4；lengthFieldOffset=22）结构常量集中定义

  **QA Scenarios**:

  ```
  Scenario: 粘包半包（happy path，文档 16.5 核心）
    Tool: Bash (JUnit + EmbeddedChannel)
    Steps:
      1. `mvnw.cmd test -Dtest=FrameCodecTest`
      2. 断言：writeOutbound 两帧合并后 writeInbound 解出 2 个 ProbeFrame 字段全等
      3. 断言：单帧字节流 split 在任意位置（如 10 字节处）分两次入站 → 解出 1 帧完整
    Expected Result: 全过
    Evidence: .sisyphus/evidence/task-25-sticky-half-frame.txt

  Scenario: 坏 magic 丢弃不炸通道（edge）
    Tool: Bash (JUnit)
    Steps:
      1. 构造 magic=0x12345678 的帧入站 → 断言无 ProbeFrame 输出且 EmbeddedChannel 仍 open
    Expected Result: 静默丢弃
    Evidence: .sisyphus/evidence/task-25-bad-magic.txt
  ```

  **Commit**: YES
  - Message: `feat(network): big-endian probe frame codec with tcp/udp pipelines`
  - Files: network/protocol/**
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 26. TcpEchoServer(9001) + UdpEchoServer(9002) + 生命周期

  **What to do**:
  - `network/tcp/TcpEchoServer`：Netty ServerBootstrap（NioEventLoopGroup）、端口读配置 `chatroom.tcp-port`；pipeline：LengthFieldBasedFrameDecoder → FrameDecoder → **PING→回 PONG（同 requestId、保留发送方 sentAtNs 原值以便 RTT 计算）；DATA→原样回弹同 payload（带宽测试依赖，见 11.1"服务器原样回弹"）** → FrameEncoder
  - `network/udp/UdpEchoServer`：Bootstrap + NioDatagramChannel；DatagramPacket 解码 → PING → 回 PONG（目标地址=来源）；DATA → 原样回弹（ACK 帧仅用于 T28 UDP 结束统计交换）
  - **启动/关闭**：实现 Spring `SmartLifecycle`（autoStartup=true，phase 适中）：start() 中 bind 异步、记录 Channel；stop() 中 `channel.close()` + `eventLoopGroup.shutdownGracefully().sync()`；两服务独立 bean
  - 异常报文只影响当前请求（pipeline 中 catch/log，不关连接）
  - 测试：生命周期（context 启动后 Test-NetConnection 9001/9002 通；context close 后端口释放、线程数回落——`Thread.getAllStackTraces().size()` 或线程名 Netty 计数归零）；真实 socket 客户端（测试内原生 Socket）发 PING 收 PONG 且 requestId 一致；UDP 同理（DatagramSocket）

  **Must NOT do**:
  - 不在 Spring 启动主线程同步 bind（异步启动）
  - 关闭不得泄漏 EventLoopGroup 线程

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Netty 服务端 + Spring 生命周期集成
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖 T25）
  - **Parallel Group**: Wave 5 第二步
  - **Blocks**: T27, T28, T29 联调
  - **Blocked By**: T25

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:521-529` — 10.2 服务（端口/异步启动/优雅关闭/PING→PONG/异常隔离）
  - `doc/聊天室系统-后端OpenCode实施文档.md:714-720` — 16.5 Netty 启停不泄漏线程

  **Acceptance Criteria**:
  - [ ] 9001/9002 随应用启动监听、随应用停止释放
  - [ ] 原生 Socket/Datagram 客户端 PING→PONG 验证
  - [ ] 线程泄漏测试通过

  **QA Scenarios**:

  ```
  Scenario: 服务启停与回弹（happy path）
    Tool: Bash (JUnit：@SpringBootTest 起 context + 原生 socket)
    Steps:
      1. context 启动 → new Socket("127.0.0.1", 9001) 成功
      2. 写一帧 PING(requestId=42) → 读一帧 → 断言 type==PONG 且 requestId==42
      3. DatagramSocket 发 UDP PING(requestId=43) 到 9002 → 收 PONG requestId==43
      4. context.close() → 1s 内 Netty 线程计数归零（Runtime 线程名扫描）
    Expected Result: 4 步全过
    Evidence: .sisyphus/evidence/task-26-echo-lifecycle.txt

  Scenario: UDP 超大报文（edge）
    Tool: Bash (JUnit)
    Steps:
      1. 发 2048 字节 UDP 包 → 服务端不崩溃（可无响应/丢弃），后续正常 PING 仍通
    Expected Result: 服务存活
    Evidence: .sisyphus/evidence/task-26-udp-oversize.txt
  ```

  **Commit**: YES
  - Message: `feat(network): tcp/udp echo servers with smart lifecycle`
  - Files: network/tcp/TcpEchoServer.java、network/udp/UdpEchoServer.java
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 27. 延时测试 API + 统计 + CSV + 入库

  **What to do**:
  - `POST /api/network-tests/latency`（body：protocol/host/port/samples/intervalMs/timeoutMs/scenario，默认 TCP/30 样本/100ms/1000ms/LAN_IDLE；校验：samples 1-1000、目标**限本机或 RFC1918 私有网段**（127.0.0.0/8、10/8、172.16/12、192.168/16——非法目标 400））
  - `network/service/LatencyTestService`：
    - TCP：单连接顺序发 PING（requestId=序号）→ 等 PONG（SO_TIMEOUT=timeoutMs，超时记 status=TIMEOUT 继续下一样本，**不永久阻塞**）；UDP：等 PONG 带超时重试语义（丢包计 LOST）
    - `System.nanoTime()` 计 RTT；**收完或超时立即记 receivedAtNs**
  - 统计（`network/service/StatisticsCalculator`，纯函数可单测）：平均值/最小/最大/方差/标准差/P50/P95（线性插值或最近秩，实现注明）；UDP 丢包率=丢失/发送
  - 持久化：network_test_runs 一行（参数+统计+csv_path）；**原始样本 CSV**（头：`runId,scenario,protocol,target,sequence,sentAtNs,receivedAtNs,rttMs,status`——与文档 549 行逐字一致）写入 `chatroom.log-root`
  - 响应：ApiResponse.ok(统计摘要 + runId + csvPath)
  - 测试：StatisticsCalculator 已知数据断言（如 [1..10] 的 P50=5.5 或按实现、P95、方差公式验证）；UDP timeoutMs=200 且不响应 → 30 样本在 30×(200+ε) 内完成（**不永久阻塞**验证）；目标 8.8.8.8 → 400 拒绝

  **Must NOT do**:
  - 不允许测试任意公网地址（形成扫描接口）
  - 统计不得伪造（真实 nanoTime 测量）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 统计计算精确性 + 超时并发语义
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T28 并行，均依赖 T26）
  - **Parallel Group**: Wave 5 第三步
  - **Blocks**: T34
  - **Blocked By**: T26, T5（network_test_runs）

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:531-553` — 10.3 延时测试 API（请求体/统计项/CSV 格式/私有网段限制）
  - `doc/聊天室系统-后端OpenCode实施文档.md:714-720` — 16.5 统计/UDP 超时测试

  **Acceptance Criteria**:
  - [ ] StatisticsCalculator 单测（已知向量）
  - [ ] API 集成测试：本地 TCP 30 样本 200 + CSV 存在且行数=样本数（+头）
  - [ ] 超时路径不挂起 + 公网目标 400

  **QA Scenarios**:

  ```
  Scenario: 本机 TCP 延时测试（happy path）
    Tool: Bash (curl)
    Preconditions: 应用运行（9001 已监听）；qauser token
    Steps:
      1. `curl.exe -s -X POST http://localhost:8080/api/network-tests/latency -H "Authorization: Bearer <t>" -H "Content-Type: application/json" -d '{"protocol":"TCP","host":"127.0.0.1","port":9001,"samples":30,"intervalMs":50,"timeoutMs":1000,"scenario":"LOCAL_SMOKE"}'`
      2. 断言 code=0；data.samples==30；avgRttMs>0；p95Ms>=p50Ms>=minMs
      3. data/logs/network-tests/ 下新 CSV：首行=表头，共 31 行
    Expected Result: 统计与 CSV 完整
    Evidence: .sisyphus/evidence/task-27-latency-tcp.txt（响应 JSON + CSV 前几行）

  Scenario: 公网目标拒绝（edge 安全）
    Tool: Bash (curl)
    Steps:
      1. host="8.8.8.8" → 断言 400（message 提示目标受限）
    Expected Result: 400
    Evidence: .sisyphus/evidence/task-27-public-target-400.txt

  Scenario: UDP 超时不阻塞（edge）
    Tool: Bash (JUnit：端口指向无服务端口)
    Steps:
      1. 测试以 timeoutMs=100 跑 10 样本 → 总耗时 < 10×(100+500ms buffer) 且 status 全 TIMEOUT/LOST
    Expected Result: 有限时间完成
    Evidence: .sisyphus/evidence/task-27-udp-timeout-bounded.txt
  ```

  **Commit**: YES
  - Message: `feat(network): latency test api with stats, csv and target guard`
  - Files: network/service/**、network/mapper（如有 run 持久化 mapper）+ XML
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 28. 带宽测试 API（TCP/UDP）

  **What to do**:
  - `POST /api/network-tests/bandwidth`（body：protocol/durationSeconds(5-10 默认 5)/messageSize(默认 65536)/host/port/scenario；TCP 走 9001、UDP 走 9002 由调用方指定）
  - TCP 模式：单连接持续 duration 秒发 **64KiB DATA 帧**；服务端对 DATA **原样回弹**（T26 已实现，语义与本任务对齐）；按实际收发字节+持续时长算 **Mbps 与 MiB/s**；同时记录平均往返延时与错误数
  - UDP 模式：单报文 payload ≤1200 字节**带序号**；统计发送数/接收数/丢失/重复/乱序（序号窗口检测）；**吞吐只按成功接收的有效负载**；结束时发 END 帧（type=5）交换最终统计（服务端回 ACK 汇总其收到计数）
  - 结果入 network_test_runs + CSV（每秒或每 100 报文一行采样）+ ApiResponse
  - 目标网段校验同 T27
  - 测试：本机 TCP 带宽 5s → 吞吐 > 0 且 Mbps 合理（本机回环应 > 100Mbps 量级，不做硬阈值，断言>1Mbps）；UDP 丢包/乱序统计在**模拟丢包**（差错模拟器或本地丢包 mock）下正确；重复/乱序计数单元测试（手工构造序号序列）

  **Must NOT do**:
  - 不伪造吞吐数据（真实测量）
  - UDP 不得发 >1200 字节报文

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - Reason: 两种协议的吞吐统计模型差异大（TCP 字节流 vs UDP 序号语义）
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T27 并行）
  - **Parallel Group**: Wave 5 第三步
  - **Blocks**: T34
  - **Blocked By**: T26

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:557-573` — 11 节带宽测试（TCP 连续发 64KiB/UDP 序号/统计项/结束报文）
  - `doc/聊天室系统-后端OpenCode实施文档.md:714-720` — 16.5 UDP 丢包乱序测试

  **Acceptance Criteria**:
  - [ ] TCP/UDP 带宽 API 各 1 集成测试 + CSV
  - [ ] UDP 序号统计（丢失/重复/乱序）单元测试

  **QA Scenarios**:

  ```
  Scenario: 本机 TCP 带宽（happy path）
    Tool: Bash (curl)
    Steps:
      1. `curl.exe -s -X POST http://localhost:8080/api/network-tests/bandwidth -H "Authorization: Bearer <t>" -H "Content-Type: application/json" -d '{"protocol":"TCP","host":"127.0.0.1","port":9001,"durationSeconds":3,"messageSize":65536,"scenario":"BW_SMOKE"}'`
      2. 断言 code=0；throughputMbps>1；errorCount 有值；CSV 生成
    Expected Result: 真实吞吐数据
    Evidence: .sisyphus/evidence/task-28-bandwidth-tcp.txt

  Scenario: UDP 统计正确性（happy path）
    Tool: Bash (JUnit：构造序列 [0,1,3,3,5] → 丢失2、重复1、乱序1)
    Steps:
      1. StatisticsCalculator/BandwidthStats 单测：输入序号序列 → 丢失={2,4} 重复=1 乱序≥1
    Expected Result: 计数精确
    Evidence: .sisyphus/evidence/task-28-udp-seq-stats.txt
  ```

  **Commit**: YES
  - Message: `feat(network): bandwidth test api for tcp/udp with sequence stats`
  - Files: network/service/BandwidthTestService.java 等
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 29. probe-client 独立项目

  **What to do**:
  - 新建 `D:\learning\chat_room\probe-client`：独立 Maven 项目（**不依赖 Spring**，纯 Java 21 + Netty；pom：netty-all + junit-jupiter + maven-shade 或 exec 插件；自带 mvnw 可选——直接用系统 mvn 亦可，但保持一致性建议 mvnw）
  - 复制/实现与 T25 相同的帧协议编解码（**独立副本**，两项目不共享源码）
  - CLI 入口 `probe.client.ProbeClientMain`（args 解析，无框架）：
    - `latency tcp <host> <port> <samples> [intervalMs] [timeoutMs]` / `latency udp ...`
    - `bandwidth tcp <host> <port> <durationSec> [msgSize]` / `bandwidth udp <host> <port> <durationSec> [payloadSize]`
    - `benchmark blocking <host> <port> ...` / `benchmark nio ...`（对 9101/9102，参数：并发数/消息大小/持续秒）
  - 输出：控制台摘要（平均值/P95/吞吐/丢包）+ CSV 写 `./out/`（带时间戳文件名）；**退出码 0 成功 / 1 失败**
  - 单元测试：帧编解码往返（同 T25 的粘包半包用例精简版）；CLI 参数解析（缺参 → usage 输出 + exit 1）
  - README.md：用法 + 与后端服务的端口对应
  - git init 独立仓库 + 提交

  **Must NOT do**:
  - 不引入 Spring 任何依赖（纯探针）
  - 不把 probe-client 塞进 chatBackend 模块

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 独立完整项目（协议+CLI+测试），需与后端服务真实联调
  - **Skills**: [`java-dev`]
  - `java-dev`: Java 项目结构与命名
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T26-T28 并行开发，联调在其后）
  - **Parallel Group**: Wave 5
  - **Blocks**: F3（终审 QA 用它联调）
  - **Blocked By**: T25（协议格式，仅文档级依赖——可与 T25 之后立即开始）

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:505-553` — 协议格式 + 延时/带宽参数（客户端行为规格）
  - `doc/聊天室系统-后端OpenCode实施文档.md:574-586` — 11.3 实验步骤（场景至少 10 轮——CLI 支持轮次参数 `rounds`）
  - `doc/聊天室系统-后端OpenCode实施文档.md:754-762` — 总提示词：OpenCode 负责 backend/ 与 probe-client/

  **Acceptance Criteria**:
  - [ ] `mvn -f probe-client/pom.xml test package` 全绿
  - [ ] 对运行中后端（9001/9002）真实执行 latency/bandwidth 并产 CSV
  - [ ] 错误参数 → usage + exit 1

  **QA Scenarios**:

  ```
  Scenario: 本机 TCP 延时探针（happy path）
    Tool: Bash (PowerShell)
    Preconditions: 后端应用运行（9001 监听）
    Steps:
      1. `mvn -f D:\learning\chat_room\probe-client\pom.xml -q compile exec:java -Dexec.mainClass=probe.client.ProbeClientMain -Dexec.args="latency tcp 127.0.0.1 9001 10 100 1000"`
      2. 断言 exit 0；控制台含 avg/p95；out/ 下新 CSV 含 10 行样本
    Expected Result: 真实测量数据落盘
    Evidence: .sisyphus/evidence/task-29-probe-latency-tcp.txt

  Scenario: 参数缺失 usage（edge）
    Tool: Bash
    Steps:
      1. args="latency tcp" （缺 host/port）→ 断言 exit 1 且输出 usage
    Expected Result: 优雅失败
    Evidence: .sisyphus/evidence/task-29-probe-usage.txt
  ```

  **Commit**: YES（probe-client 独立仓库）
  - Message: `feat: initial probe client with latency/bandwidth/benchmark commands`
  - Files: D:\learning\chat_room\probe-client\**（全部）
  - Pre-commit: `mvn -f pom.xml test -q`

- [ ] 30. BlockingTcpEchoServer(9101) + NioTcpEchoServer(9102)

  **What to do**:
  - `network/benchmark/BlockingTcpEchoServer`：`chatroom.blocking-benchmark-port=9101`；**一个连接一个线程**（`Executors.newCachedThreadPool` 或固定线程池，accept 循环阻塞 IO；手工阻塞套接字实现——不引入 Netty）；帧解析复用 ProbeFrame 解码逻辑（阻塞流读取：先读 22 字节头再读 payload，处理半包）
  - `network/benchmark/NioTcpEchoServer`：`chatroom.nio-benchmark-port=9102`；**Java NIO Selector**（单 Selector 多路复用 + Attachment 缓冲区拼帧）或 Netty EventLoop——**选原生 Java NIO Selector**（文档"使用 Java NIO Selector 或 Netty EventLoop"，原生更能体现对比教学价值；报告可注明）
  - 两服务行为相同：收 PING→PONG（同 requestId）、收 DATA→原样回弹；优雅关闭（SmartLifecycle；Blocking 关线程池 shutdownNow+awaitTermination；NIO 关 selector+channel）
  - 对比公平性保障：两服务**消息处理逻辑（帧编解码路径）共用同一套纯函数**，仅 IO 模型不同
  - 测试：两端口启停监听；原生客户端 PING→PONG 各 1 例；关闭后端口释放、线程归零

  **Must NOT do**:
  - 不得用同一 IO 模型实现两个服务（否则对比失真）
  - Blocking 不得用固定小线程池导致 500 并发饥饿（cached 或 ≥600 容量）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 原生 NIO Selector 手写（缓冲区/选择键/半包拼装）与线程模型对比是全项目技术难点
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T32/T33 并行）
  - **Parallel Group**: Wave 6
  - **Blocks**: T31, T34
  - **Blocked By**: T5, T25（复用帧解析）

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:589-603` — 12 节（9101 Blocking/9102 NIO Selector、行为相同、优雅关闭）
  - `doc/聊天室系统-后端OpenCode实施文档.md:714-720` — 16.5 Netty 启停/压测参数测试

  **Acceptance Criteria**:
  - [ ] 9101（线程/连接模型）+ 9102（Selector 模型）双服务启停测试
  - [ ] 两服务 PING→PONG 行为一致（同一测试跑两端口）

  **QA Scenarios**:

  ```
  Scenario: 双端口回弹（happy path）
    Tool: Bash (JUnit：context 启动 + 原生 socket ×2)
    Steps:
      1. Socket 9101 发 PING(req=1) → PONG(req=1)
      2. Socket 9102 发 PING(req=2) → PONG(req=2)
      3. 两 socket 各发 1KiB DATA → 收到同 payload 回弹
    Expected Result: 两服务行为一致
    Evidence: .sisyphus/evidence/task-30-dual-echo.txt

  Scenario: 关闭释放（edge）
    Tool: Bash (JUnit)
    Steps:
      1. context.close() → 1-2s 内 9101/9102 端口不可连（ConnectException）且无残留 "BlockingEcho"/"NioEcho" 线程
    Expected Result: 端口+线程双释放
    Evidence: .sisyphus/evidence/task-30-shutdown-release.txt
  ```

  **Commit**: YES
  - Message: `feat(benchmark): blocking thread-per-conn 9101 + nio selector 9102 echo servers`
  - Files: network/benchmark/**
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 31. 压测客户端 + 基准 API + 指标 CSV

  **What to do**:
  - `network/benchmark/BenchmarkClient`（服务端进程内运行，避免课程演示依赖外部）：参数（targetHost/targetPort/concurrency 50/100/200/500、messageSize 1/16/64 KiB、durationSeconds 默认 30、repeats 默认 3）
  - 行为：N 个并发工作线程/连接 → 持续 duration 秒发 DATA→收回弹（requestId 计数）；统计**成功请求数、吞吐量、平均/P95 延时、错误率**；**服务端线程数**（ThreadMXBean 或线程名计数）、CPU（OperatingSystemMXBean processCpuLoad）、内存（heap used）周期采样
  - `POST /api/network-tests/benchmark`（body：上述参数 + serverType BLOCKING/NIO + scenario）：同步执行（duration 可缩短用于冒烟）→ network_test_runs 入库 + **真实 CSV**（每秒一行采样 + 汇总行）+ ApiResponse 返回汇总
  - **公平性**：文档要求两服务同机器/JVM/消息内容/持续时间——API 内固定由服务端本机发起（127.0.0.1），消息内容同一随机种子
  - 测试：短参数（concurrency=5、duration=2s）跑 BLOCKING 与 NIO 各一次 → CSV 生成、吞吐>0、错误率字段存在；参数校验（concurrency>500 或 duration>60 拒绝 400——防误用成压垮自己的接口）；**完整矩阵（50-500×3 尺寸×30s×3 次）不进自动化**，由 probe-client/手动执行（文档 11.3 实验要求，报告用）
  - 备注：完整对比实验属于课程报告实验环节，执行时手动跑 API（或 probe-client benchmark 命令），本任务保证参数化能力

  **Must NOT do**:
  - 不伪造吞吐/延时数据（一切来自真实测量）
  - 自动化测试不得跑完整 36 组合矩阵（时间不可行）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 并发压测器 + 多指标采集（线程/CPU/内存）正确性
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖 T30）
  - **Parallel Group**: Wave 6 第二步
  - **Blocks**: T34
  - **Blocked By**: T30, T27（复用统计与 CSV 模式）

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:595-603` — 12 节压测参数矩阵 + 输出指标 + 公平性要求
  - `doc/聊天室系统-后端OpenCode实施文档.md:714-720` — 16.5 压测参数有效测试

  **Acceptance Criteria**:
  - [ ] 短参数 BLOCKING/NIO 双跑集成测试 + CSV
  - [ ] 指标字段齐全（吞吐/avg/P95/错误率/线程数/CPU/内存）

  **QA Scenarios**:

  ```
  Scenario: 短时基准冒烟（happy path）
    Tool: Bash (curl)
    Preconditions: 应用运行
    Steps:
      1. `curl.exe -s -X POST http://localhost:8080/api/network-tests/benchmark -H "Authorization: Bearer <t>" -H "Content-Type: application/json" -d '{"serverType":"BLOCKING","concurrency":5,"messageSize":1024,"durationSeconds":2,"scenario":"BM_SMOKE"}'`
      2. 断言 code=0；successfulRequests>0；throughputMbps>0；threadCount 字段有值
      3. CSV 文件生成（≥2 行采样 + 汇总）
      4. serverType=NIO 同参数重跑 → 同样成功
    Expected Result: 两模式真实数据
    Evidence: .sisyphus/evidence/task-31-benchmark-smoke.txt

  Scenario: 超限参数拒绝（edge）
    Tool: Bash (curl)
    Steps:
      1. concurrency=10000 → 400（参数上限）
    Expected Result: 400
    Evidence: .sisyphus/evidence/task-31-param-limit.txt
  ```

  **Commit**: YES
  - Message: `feat(benchmark): in-process benchmark client with metrics csv`
  - Files: network/benchmark/BenchmarkClient.java、benchmark API controller/service
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 32. RTC 信令中转

  **What to do**:
  - `rtc/RtcSignalService`（由 T16 转发骨架完善）：
    - `RTC_OFFER`：payload{targetUserId, sdp?}——**校验目标在线**（registry.get(targetUserId)!=null）→ 转发 envelope（type=RTC_OFFER，payload 附 fromUserId/fromUsername）给目标 session；**不解析 SDP 内容**
    - `RTC_ANSWER`：同上回传发起方
    - `RTC_ICE`：payload{targetUserId, candidate?}——转发（不解析、不保存）
    - 目标离线 → 给发送方 ERROR（同 requestId，reason="目标离线"）
    - **不持久化任何 RTC/P2P 消息**（不写 chat_messages、不写任何表）
  - ChatWebSocketHandler 的 RTC_* 路由指向本 Service（T16 骨架已通）
  - 集成测试（两客户端 AUTH）：A→B RTC_OFFER → B 收到（type/payload.fromUserId==A）；B→A RTC_ANSWER → A 收到；A→B RTC_ICE 往返；A→离线用户 RTC_OFFER → A 收 ERROR；**断言 DB 无新增行**（RTC 信令零持久化）

  **Must NOT do**:
  - 不解析/校验 SDP 内容（透传）
  - 不保存 P2P 消息或 P2P 文件（DataChannel 数据不经服务端）
  - 若最终用 TURN，报告中如实说明（本项目不部署 TURN）

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: 集成测试为主（双客户端信令往返时序）
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T30/T33 并行）
  - **Parallel Group**: Wave 6
  - **Blocks**: T34
  - **Blocked By**: T16

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:607-619` — 13 节（三消息类型/在线校验/不解析 SDP/不保存/DataChannel 不经过服务器）
  - `doc/聊天室系统-后端OpenCode实施文档.md:669-672` — B5 验收"信令只转发给目标用户"

  **Acceptance Criteria**:
  - [ ] OFFER/ANSWER/ICE 三类往返测试 + 离线 ERROR + 零持久化断言

  **QA Scenarios**:

  ```
  Scenario: 信令三角往返（happy path）
    Tool: Bash (JUnit 双 StandardWebSocketClient)
    Steps:
      1. userA/userB AUTH
      2. A 发 {"type":"RTC_OFFER","requestId":"r10","payload":{"targetUserId":<uidB>,"sdp":"v=0..."}} 
      3. 断言 B 收 RTC_OFFER 且 payload.fromUserId==<uidA>
      4. B 回 RTC_ANSWER(targetUserId=<uidA>) → 断言 A 收到
      5. 双向各发一 RTC_ICE → 双方收到
      6. 断言全程 chat_messages/stored_files 行数零变化
    Expected Result: 全过 + 零持久化
    Evidence: .sisyphus/evidence/task-32-rtc-relay.txt

  Scenario: 离线目标（edge）
    Tool: Bash (JUnit)
    Steps:
      1. A 发 RTC_OFFER 给离线 uidC → A 收 ERROR requestId=="r11"
    Expected Result: 明确错误
    Evidence: .sisyphus/evidence/task-32-rtc-offline.txt
  ```

  **Commit**: YES
  - Message: `feat(rtc): signaling relay for offer/answer/ice with online check`
  - Files: rtc/RtcSignalService.java、chat/websocket/ChatWebSocketHandler.java（路由）
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 33. 管理接口 + RuntimeSettings + 禁用踢线

  **What to do**:
  - `admin/AdminController`（/api/admin/** 已由 T10 拦截器限 ADMIN）：
    - `GET /api/admin/users`：分页用户列表（脱敏：id/username/displayName/role/enabled/createdAt，无哈希）
    - `PUT /api/admin/users/{id}/enabled`（body {enabled:bool}）：更新 users.enabled；**禁用时若目标在线 → registry 关闭其 WS 连接**（触发下线广播）；不能禁用自己（400）
    - `PUT /api/admin/settings/error-rate`（body {errorRate:0.0-1.0}）：写 RuntimeSettingsService（T21 建立的内存态）→ **即时生效**（下一次分块上传即用新值）；范围校验 400
    - `POST /api/admin/notices`（body {title, content}）：ServerNoticeService.broadcastNotice（SERVER_NOTICE；压测场景只发"准备通知"，由客户端确认后才发起大流量——**服务端不自动触发压测**）
  - `admin/AdminUserService` 薄服务层
  - 测试：
    - USER token 全部 403（4 端点矩阵）
    - 禁用在线用户 → 该用户 WS onClose 在 2s 内 + USER_OFFLINE 广播 + 其旧 token 再用 → 401
    - error-rate 改 0.5 → 立即上传分块按新概率（seed 环境确定性验证序列变化）
    - notice → 在线客户端收到 SERVER_NOTICE

  **Must NOT do**:
  - 广播不得直接触发客户端大流量（只发通知）
  - admin 接口不得绕过拦截器（统一 403 语义）

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: 跨模块集成（WS 踢线 + 运行时设置 + 通知）
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 T30/T32 并行）
  - **Parallel Group**: Wave 6
  - **Blocks**: T34
  - **Blocked By**: T10, T14, T16, T21

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:622-632` — 14 节管理功能（4 端点/禁用踢线/差错率即时生效/准备通知语义）
  - `doc/聊天室系统-后端OpenCode实施文档.md:669-672` — B5 验收"配置修改无需重启"

  **Acceptance Criteria**:
  - [ ] 4 端点全部实现 + USER 403 矩阵
  - [ ] 禁用踢线 e2e + error-rate 即时生效测试通过

  **QA Scenarios**:

  ```
  Scenario: 管理四端点（happy path）
    Tool: Bash (curl)
    Preconditions: admin token（admin/admin123456 登录）
    Steps:
      1. `curl.exe -s http://localhost:8080/api/admin/users -H "Authorization: Bearer <adminT>"` → code=0 且分页结构
      2. PUT enabled=false（对 qauser3，其 WS 在线）→ code=0
      3. 断言 qauser3 的 WS 客户端 2s 内 onClose
      4. `curl.exe -X PUT http://localhost:8080/api/admin/settings/error-rate -H "Authorization: Bearer <adminT>" -d '{"errorRate":0.5}'` → code=0
      5. `curl.exe -s -X POST http://localhost:8080/api/admin/notices -H "Authorization: Bearer <adminT>" -d '{"title":"t","content":"c"}'` → 在线测试客户端收到 SERVER_NOTICE
    Expected Result: 5 步全过
    Evidence: .sisyphus/evidence/task-33-admin-matrix.txt

  Scenario: USER 越权 + 禁用后 Token 失效（edge）
    Tool: Bash (curl + JUnit WS)
    Steps:
      1. user token 访问 4 个 admin 端点 → 全部 403
      2. 被禁用用户旧 token 访问 /api/files → 401（拦截器复查 enabled 生效）
      3. 恢复 enabled=true（admin）→ 该用户重新登录 → 200
    Expected Result: 权限与生命周期闭环
    Evidence: .sisyphus/evidence/task-33-user-403-and-disable.txt
  ```

  **Commit**: YES
  - Message: `feat(admin): user management, runtime error-rate, notice broadcast`
  - Files: admin/**、chat/service/RuntimeSettingsService（扩展）
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 34. B5 收尾：优雅关闭全链路 + 全量构建 + 文档偏差记录

  **What to do**:
  - 优雅关闭全链路验证：应用 context close 后：9001/9002/9101/9102 四端口全部释放；Netty EventLoopGroup、Blocking 线程池、NIO Selector、WS 会话、上传临时目录（触发一次清理）全部无残留（线程名扫描 + 端口连接测试 + temp 目录检查）
  - 全量验收：`mvnw.cmd clean test package` 全绿（全项目全部测试）
  - **文档偏差记录**：在 `doc/聊天室系统-后端OpenCode实施文档.md` 末尾追加"实施偏差说明"章节：Java 25→JDK 21（本机环境）；包名 com.hlx.chatroom→fun.hatsumi.chatbackend（既有脚手架）；flyway-core→spring-boot-starter-flyway（Boot 4 模块化要求）；starter-web→starter-webmvc（Boot 4 更名）；新增 CORS 配置（前端联调需要）；admin 种子账号（V2 迁移，文档未定义引导方式）
  - 输出 B5 完成清单 + 文档 20 章"后端完成定义"逐条勾选表

  **Must NOT do**:
  - 不修改文档既有正文（只追加偏差章节）
  - 不为验收跳过失败测试

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 全链路关闭验证 + 文档合规收尾
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 6 收尾
  - **Blocks**: F1-F4
  - **Blocked By**: T24, T27, T28, T31, T32, T33

  **References**:
  - `doc/聊天室系统-后端OpenCode实施文档.md:665-672` — B5 验收标准
  - `doc/聊天室系统-后端OpenCode实施文档.md:787-802` — 20 章后端完成定义 14 条
  - `doc/聊天室系统-后端OpenCode实施文档.md:733-749` — 17 章安全可靠性边界（优雅清理要求）

  **Acceptance Criteria**:
  - [ ] 四端口 + 线程 + temp 全释放验证
  - [ ] 全量构建全绿
  - [ ] doc/ 偏差章节追加完成
  - [ ] 完成定义 14 条逐条勾选留证

  **QA Scenarios**:

  ```
  Scenario: 全链路优雅关闭（happy path）
    Tool: Bash (PowerShell)
    Preconditions: 应用运行中（含 WS 客户端 1 个在线）
    Steps:
      1. 记录 java 进程 PID；正常停止（Stop-Process 发送优雅信号不可行时：用 mvnw spring-boot:run 的 Ctrl+C 等价—— actuator /actuator/shutdown 若未启用则采用进程终止+端口/线程复核）
      2. 等待 3s；`Test-NetConnection localhost -Port 9001/9002/9101/9102` 全部 False
      3. 线程 dump 或 java 进程退出（进程结束即无泄漏）
    Expected Result: 端口全释放 + 进程退出
    Evidence: .sisyphus/evidence/task-34-graceful-shutdown.txt

  Scenario: 全量构建（happy path）
    Tool: Bash
    Steps:
      1. `mvnw.cmd clean test package` → BUILD SUCCESS；汇总测试数（surefire 报告）写入证据
    Expected Result: 全绿 + 测试计数 > 60（各阶段累计）
    Evidence: .sisyphus/evidence/task-34-full-build.txt

  Scenario: 完成定义逐条核对（文档 20 章）
    Tool: Bash（人工核对表由 agent 逐条验证）
    Steps:
      1. 14 条逐条 curl/读代码/查 DB 验证（如"无 JPA Repository"→ grep JpaRepository 零匹配）
    Expected Result: 13/13
    Evidence: .sisyphus/evidence/task-34-completion-checklist.txt
  ```

  **Commit**: YES
  - Message: `docs(doc): record implementation deviations + b5 acceptance`
  - Files: doc/聊天室系统-后端OpenCode实施文档.md（追加章节）
  - Pre-commit: `mvnw.cmd test -q`

- [ ] 35. MySQL 集成恢复（用户部署 MySQL 后执行）

  **What to do**:
  - 前置：用户已部署/启用 MySQL（优先复用本机 MySQL81 实例），提供 root 或授权路径
  - 执行：`CREATE DATABASE chatroom_lab; CREATE DATABASE chatroom_lab_test;` + chatroom 用户授权两库
  - 移除 pom surefire `excludedGroups=db` 门控 → `mvnw.cmd clean test` 全量（全部 @Tag("db") 集成测试首次真实执行，暴露累积的 DB 层问题并修复）
  - 逐任务补跑 `.sisyphus/evidence/task-{N}-deferred.txt` 中全部 DEFERRED-DB 验收项（Flyway 建表/admin 种子/服务级启动冒烟/真实 HTTP 与 WS QA/越权矩阵/e2e 全链路）
  - 全部通过后进入 F 终审波次

  **Must NOT do**:
  - 不得跳过任何 deferred 项（诚实补跑）
  - 不得为通过测试放宽断言

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`java-dev`]
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: F1-F4（条件）
  - **Blocked By**: T34 + 用户部署 MySQL（外部条件）

  **References**: 本计划 "MySQL Deferred 执行模式" 章节

  **Acceptance Criteria**:
  - [ ] 全量测试（含 db tag）全绿
  - [ ] 全部 deferred 验收补跑留证
  - [ ] deferred 清单清零

  **QA Scenarios**: 逐 deferred 文件补跑（每项原 QA 步骤照执行，证据覆盖原 deferred 文件）

  **Commit**: YES
  - Message: `test(db): restore mysql integration, run full deferred acceptance`

---

## Final Verification Wave (MANDATORY — after ALL implementation tasks)

> 4 review agents run in PARALLEL. ALL must APPROVE. Rejection → fix → re-run.

- [ ] F1. **计划合规审计** — `oracle`
  通读本计划全文。逐条核对 "Must Have"：读文件/curl 端点/跑命令验证实现存在。逐条核对 "Must NOT Have"：在代码库中搜索禁止模式（`spring-boot-starter-security`、`JpaRepository`、`readAllBytes`、`${}` SQL、明文密码、MD5/SHA-1）——发现即 file:line 拒绝。检查 `.sisyphus/evidence/` 证据文件齐全。对比交付物与计划。
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [34/34] | VERDICT: APPROVE/REJECT`

- [ ] F2. **代码质量审查** — `unspecified-high`
  运行 `mvnw.cmd clean test package` + 检查全部变更文件：`as any` 式强转、空 catch、生产代码 console 输出（System.out）、注释掉的代码、未使用导入。AI slop 检查：过度注释、无意义抽象、泛型命名（data/result/item/temp）。确认分层规则（Controller 不调 Mapper、Handler 不拼 SQL）。
  Output: `Build [PASS/FAIL] | Tests [N pass/N fail] | Files [N clean/N issues] | VERDICT`

- [ ] F3. **真实手工 QA** — `unspecified-high`
  从干净状态启动（先删 data/ 目录），执行每个任务的 QA 场景（按证据路径核对），重点跨任务集成：注册→登录→WS 聊天→传文件→下载→网络测试→压测→WebRTC 信令→admin 全链路。边界：空输入、非法 token、路径穿越、超大分块。证据存 `.sisyphus/evidence/final-qa/`。
  Output: `Scenarios [N/N pass] | Integration [N/N] | Edge Cases [N tested] | VERDICT`

- [ ] F4. **范围保真检查** — `deep`
  对每个任务：读"What to do"，对照实际代码 diff。1:1 校验——规格内全部实现（无遗漏），规格外无多余（无蔓延）。核对 "Must NOT do" 合规。检测跨任务污染（任务 N 改任务 M 的文件）。标记未入账变更（`room-chat/` 必须零改动）。
  Output: `Tasks [34/34 compliant] | Contamination [CLEAN/N issues] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

- **T1**: `build(pom): fix dependencies for Boot 4 stack (webmvc/websocket/flyway/mybatis-plus/netty/jwt)` — pom.xml, .gitignore
- **T2**: `chore(config): package structure, application.yml, jwt validation, cors` — 全新文件
- **T3**: `feat(db): flyway V1 schema + V2 admin seed` — migration SQL
- **T4**: `feat(common): unified ApiResponse + global exception handler + health`
- **T5**: `feat(config): mybatis-plus pagination + test infrastructure`
- **T6**: `test(b0): phase acceptance` — 无新码则并入 T5 提交
- **T7-T34**: `feat(module): ...` / `test(module): ...`（每任务一提交，测试与实现同提交）
- **T29**: `feat(probe-client): independent network probe client`（独立 git 仓库 init）
- **T34**: `docs(doc): record deviations (JDK21, package name, flyway starter)` — doc/ 更新
- **F1-F4**: `fix(review): address final review findings`（如有）

统一规范：提交前跑 `mvnw.cmd test -q`；提交信息用 conventional commits；绝不提交 `.env`/secret/`data/` 目录。

---

## Success Criteria

### Verification Commands
```powershell
# 全量构建（最终验收）
mvnw.cmd clean test package   # 期望: BUILD SUCCESS, 全部测试通过

# 启动冒烟（需先设 secret）
$env:CHATROOM_JWT_SECRET="dev-secret-0123456789abcdef0123456789abcdef"; mvnw.cmd spring-boot:run
# 另一终端: curl http://localhost:8080/api/health   # 期望: {"code":0,...}

# 数据库
# 期望: 6 张表存在, users 含 admin, password_hash 均为 BCrypt 格式 ($2a$...)
mysql -uchatroom -pchatroom123 chatroom_lab -e "SHOW TABLES; SELECT username, role FROM users;"

# probe-client
D:\learning\chat_room\probe-client\mvnw.cmd -q exec:java -Dexec.args="latency tcp 127.0.0.1 9001 10"
# 期望: 退出码 0, CSV 生成
```

### Final Checklist
- [ ] 文档第 20 章"后端完成定义"14 条全部满足
- [ ] 文档第 16 章 5 组测试清单全部有 JUnit 覆盖
- [ ] 无 Security Starter / JPA / 明文密码 / 硬编码 secret / 伪造数据
- [ ] 前端 room-chat 零改动
- [ ] 所有 QA 证据文件存在于 `.sisyphus/evidence/`
