# 聊天室系统后端 OpenCode 实施文档

> 项目：网络软件设计——聊天室系统  
> 姓名：黄龙翔  
> 学号：2023010901026  
> 本文档只负责后端，前端实现以《聊天室系统-前端OpenCode实施文档.md》为准。

---

## 1. 后端目标与固定技术选型

后端需要同时承担 Web 业务服务和网络协议实验：

- 用户注册、密码登录和轻量 JWT Token；
- 多客户端 WebSocket 聊天、在线列表、服务器广播；
- 公共和私人文件、分块上传、流式下载、差错模拟和重传；
- 原生 TCP/UDP 延时与带宽测试；
- 阻塞多线程与 NIO Selector 性能对比；
- WebRTC Offer、Answer、ICE 信令中转。

固定技术栈：

- Java 25；
- Spring Boot 4.1.1；
- Spring Web MVC；
- Spring WebSocket；
- MyBatis + MyBatis-Plus 3.5.17；
- MySQL 8；
- Flyway；
- Netty；
- `spring-security-crypto` 中的 BCrypt，仅用于密码哈希；
- Auth0 `java-jwt` 4.6.0，仅用于 Token 生成和解析；
- JUnit 5 + Spring Boot Test。

明确禁止：

- 不引入 `spring-boot-starter-security`；
- 不搭建 OAuth2、认证服务器、Session 集群或 Refresh Token 系统；
- 不引入 Spring Data JPA；
- 不在数据库保存明文密码；
- 不使用 MD5、SHA-1 或普通 SHA-256 直接保存密码；
- 不改成 Node、Go 或 Python 后端。

这套登录方案是课程项目的轻量实现：BCrypt 负责不可逆密码哈希，JWT 负责无状态 Token。这里应称为“密码哈希”，不要写成可解密的“密码加密”。

---

## 2. 技术兼容性与依赖

Spring Boot 4.1.1 支持 Java 25。MyBatis-Plus 从 3.5.13 起提供 Spring Boot 4 Starter，本项目固定使用 3.5.17。

### 2.1 `pom.xml` 核心内容

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
</parent>

<properties>
    <java.version>25</java.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
        <version>3.5.17</version>
    </dependency>
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-mysql</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-crypto</artifactId>
    </dependency>
    <dependency>
        <groupId>com.auth0</groupId>
        <artifactId>java-jwt</artifactId>
        <version>4.6.0</version>
    </dependency>

    <dependency>
        <groupId>io.netty</groupId>
        <artifactId>netty-all</artifactId>
    </dependency>

    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

若 Spring Boot 4.1.1 BOM 已管理 Netty、Spring Crypto、MySQL 和 Flyway 版本，不要重复指定版本。只有未纳入 BOM 的 MyBatis-Plus 与 `java-jwt` 在本文中固定版本。

### 2.2 启动类

```java
@SpringBootApplication
@MapperScan({
    "com.hlx.chatroom.user.mapper",
    "com.hlx.chatroom.chat.mapper",
    "com.hlx.chatroom.file.mapper",
    "com.hlx.chatroom.network.mapper"
})
public class ChatroomApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatroomApplication.class, args);
    }
}
```

Java 25 可以使用标准稳定语法，但本项目不依赖 Preview Feature，避免 OpenCode 生成必须添加 `--enable-preview` 的代码。

---

## 3. 后端目录结构

```text
backend/
├─ pom.xml
├─ mvnw
├─ mvnw.cmd
└─ src/
   ├─ main/
   │  ├─ java/com/hlx/chatroom/
   │  │  ├─ ChatroomApplication.java
   │  │  ├─ common/
   │  │  │  ├─ api/
   │  │  │  ├─ exception/
   │  │  │  └─ util/
   │  │  ├─ config/
   │  │  ├─ auth/
   │  │  │  ├─ controller/
   │  │  │  ├─ dto/
   │  │  │  ├─ interceptor/
   │  │  │  └─ service/
   │  │  ├─ user/
   │  │  │  ├─ entity/
   │  │  │  ├─ mapper/
   │  │  │  └─ service/
   │  │  ├─ chat/
   │  │  │  ├─ entity/
   │  │  │  ├─ mapper/
   │  │  │  ├─ service/
   │  │  │  ├─ websocket/
   │  │  │  └─ protocol/
   │  │  ├─ file/
   │  │  │  ├─ controller/
   │  │  │  ├─ entity/
   │  │  │  ├─ mapper/
   │  │  │  ├─ service/
   │  │  │  └─ storage/
   │  │  ├─ network/
   │  │  │  ├─ protocol/
   │  │  │  ├─ tcp/
   │  │  │  ├─ udp/
   │  │  │  ├─ benchmark/
   │  │  │  └─ service/
   │  │  ├─ rtc/
   │  │  └─ admin/
   │  └─ resources/
   │     ├─ application.yml
   │     ├─ db/migration/V1__init.sql
   │     └─ mapper/
   └─ test/java/com/hlx/chatroom/
```

分层规则：Controller 只处理参数和响应；Service 处理业务；Mapper 只访问数据库；Entity 不直接作为接口响应；复杂 SQL 放 XML；WebSocket Handler 不直接拼 SQL。

---

## 4. 配置文件

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/chatroom_lab?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: chatroom
    password: chatroom123
  servlet:
    multipart:
      max-file-size: 2GB
      max-request-size: 2GB

mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  type-aliases-package: com.hlx.chatroom.user.entity,com.hlx.chatroom.chat.entity,com.hlx.chatroom.file.entity,com.hlx.chatroom.network.entity
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: auto

chatroom:
  jwt:
    issuer: chatroom-lab
    secret: ${CHATROOM_JWT_SECRET}
    expire-hours: 12
  storage-root: ./data/storage
  log-root: ./data/logs/network-tests
  error-simulation-rate: 0.20
  tcp-port: 9001
  udp-port: 9002
  blocking-benchmark-port: 9101
  nio-benchmark-port: 9102
```

启动时如果 `CHATROOM_JWT_SECRET` 为空或不足 32 字节，应直接失败并给出清晰提示。开发环境可以在不提交 Git 的 `.env` 或 IDE Run Configuration 中设置。

---

## 5. 数据库与 MyBatis-Plus 规范

### 5.1 核心表

#### `users`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT AUTO_INCREMENT | 主键 |
| username | VARCHAR(50) UNIQUE | 登录账号 |
| display_name | VARCHAR(50) | 显示名称 |
| password_hash | VARCHAR(100) | BCrypt 哈希 |
| role | VARCHAR(20) | USER/ADMIN |
| enabled | TINYINT(1) | 是否可登录 |
| created_at | DATETIME(3) | 创建时间 |
| updated_at | DATETIME(3) | 更新时间 |

#### `chat_messages`

字段：`id`、`sender_id`、`receiver_id`、`chat_type`、`content`、`text_color`、`sent_at`。`receiver_id` 在群聊时为空，`chat_type` 为 PRIVATE/BROADCAST/P2P。

#### `stored_files`

字段：`id`、`owner_id`、`scope`、`original_name`、`stored_name`、`relative_path`、`size_bytes`、`sha256`、`created_at`。公共文件允许 `owner_id` 为空，私人文件必须有所有者。

#### `upload_sessions`

字段：`upload_id`、`owner_id`、`scope`、`file_name`、`file_size`、`chunk_size`、`total_chunks`、`file_sha256`、`status`、`expire_at`、`created_at`。

#### `upload_chunks`

字段：`id`、`upload_id`、`chunk_index`、`chunk_size`、`chunk_sha256`、`created_at`，并建立 `(upload_id, chunk_index)` 唯一索引。

#### `network_test_runs`

保存测试类型、协议、目标、参数、开始/结束时间、平均值、方差、P95、吞吐量、丢包率和场景名称。每个原始样本同时写 CSV。

### 5.2 MyBatis-Plus 使用规则

- Entity 使用 `@TableName`、`@TableId`、`@TableField`；
- Mapper 继承 `BaseMapper<Entity>`；
- Service 可继承 `IService`/`ServiceImpl`，但复杂业务不要全部塞在通用 Service；
- 单表等值查询可使用 `LambdaQueryWrapper`；
- 消息历史分页、统计聚合和多条件文件查询写 Mapper XML；
- Controller 不直接调用 Mapper；
- 分页使用 MyBatis-Plus 分页拦截器；
- SQL 中所有外部值必须使用 `#{}` 参数，不能使用字符串拼接或 `${}`；
- Flyway 是表结构唯一来源，不启用 ORM 自动建表。

---

## 6. 统一 REST 响应

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

状态约定：

- 400：参数错误；
- 401：Token 缺失、过期或无效；
- 403：访问他人私人文件或角色不符；
- 404：资源不存在；
- 409：用户名重复、分块状态冲突；
- 500：未知错误，响应中不返回堆栈。

使用 `@RestControllerAdvice` 统一处理业务异常、参数异常和未知异常。

---

## 7. 轻量登录方案

### 7.1 为什么这样设计

本项目不使用完整 Spring Security 过滤器链。仅使用：

- `spring-security-crypto`：调用 `BCryptPasswordEncoder`；
- `java-jwt`：签发和验证 HMAC256 JWT；
- 自定义 Spring MVC `HandlerInterceptor`：检查 REST Token；
- WebSocket 建立后的第一条 `AUTH` 消息：检查长连接 Token。

依赖少、流程直观，也足以满足课程项目的用户身份验证。

### 7.2 注册

`POST /api/auth/register`

1. 检查用户名是否存在；
2. 使用 `BCryptPasswordEncoder(10)` 生成哈希；
3. 只保存 `password_hash`；
4. 创建 `data/storage/users/{userId}`；
5. 返回用户基本信息，不自动返回密码。

密码规则可以保持简单：6～32 个字符。不能在日志中输出原密码。

### 7.3 登录

`POST /api/auth/login`

1. 按 username 查询用户；
2. 检查 enabled；
3. 调用 `passwordEncoder.matches(raw, hash)`；
4. 成功后签发 JWT；
5. 失败统一返回“用户名或密码错误”，避免暴露用户是否存在。

JWT Claims：

- `sub`：用户 ID；
- `username`：用户名；
- `role`：USER/ADMIN；
- `iss`：`chatroom-lab`；
- `iat`：签发时间；
- `exp`：12 小时后。

JWT 只放最小身份信息，不放密码哈希、私人文件路径或聊天内容。

### 7.4 REST Token 拦截器

实现 `AuthInterceptor`：

- 从 `Authorization: Bearer <token>` 读取 Token；
- 验证签名、issuer、过期时间；
- 解析用户 ID；
- 查询用户是否仍启用；
- 将当前用户放入请求属性或 `UserContext`；
- 请求结束必须清理 ThreadLocal；
- 排除 `/api/auth/register`、`/api/auth/login`、`/api/health`；
- `/api/admin/**` 额外检查 ADMIN 角色。

不要把 Token 放在 URL Query 参数中。

### 7.5 WebSocket Token

连接地址仍为 `/ws/chat`。连接成功后 5 秒内，客户端必须发送：

```json
{
  "type": "AUTH",
  "requestId": "uuid",
  "timestamp": 1788422400000,
  "payload": {"token": "jwt-token"}
}
```

认证完成前只能接收 `AUTH`。成功返回 `AUTH_SUCCESS` 和在线列表；失败返回 `AUTH_FAILURE` 并关闭连接。

---

## 8. WebSocket 聊天

### 8.1 服务端组件

- `ChatWebSocketHandler`：解析和路由协议；
- `WebSocketSessionRegistry`：维护 `userId -> session`；
- `WebSocketAuthService`：验证首帧 Token；
- `PresenceService`：上线、下线、心跳；
- `ChatMessageService`：保存和查询消息；
- `RtcSignalService`：只中转 WebRTC 信令；
- `ServerNoticeService`：广播服务器通知。

### 8.2 消息信封

```json
{
  "type": "CHAT_PRIVATE",
  "requestId": "uuid",
  "timestamp": 1788422400000,
  "payload": {}
}
```

消息类型：AUTH、AUTH_SUCCESS、AUTH_FAILURE、PING、PONG、USER_LIST、USER_ONLINE、USER_OFFLINE、CHAT_PRIVATE、CHAT_BROADCAST、CHAT_MESSAGE、RTC_OFFER、RTC_ANSWER、RTC_ICE、SERVER_NOTICE、ERROR。

### 8.3 并发规则

- 会话注册表使用 `ConcurrentHashMap<Long, SessionHolder>`；
- 同一用户重复连接时关闭旧连接、保留新连接；
- 同一个 WebSocket Session 的发送动作必须串行；
- 群聊遍历在线快照，单个连接异常不能中断整个广播；
- 20 秒心跳，60 秒无响应清理；
- 消息入库成功后再向发送方返回最终消息 ID；
- 私聊目标离线时返回明确错误；
- P2P 消息不经过服务端，也不写 `chat_messages`。

---

## 9. 文件服务

### 9.1 存储目录

```text
data/storage/
├─ public/
├─ users/{userId}/
└─ temp/{uploadId}/{chunkIndex}.part
```

规则：

- 数据库只保存相对路径；
- 服务端随机生成 storedName；
- 原始文件名只用于展示和下载头；
- 所有路径 normalize 后必须仍位于 storage root；
- 私人文件 ownerId 必须等于当前 Token 用户；
- 公共文件允许所有登录用户查看和下载；
- 不使用 `Files.readAllBytes()` 处理大文件。

### 9.2 API

| 方法 | 地址 | 用途 |
| --- | --- | --- |
| GET | `/api/files?scope=PUBLIC` | 公共文件列表 |
| GET | `/api/files?scope=PRIVATE` | 当前用户私人文件 |
| POST | `/api/uploads` | 创建上传会话 |
| PUT | `/api/uploads/{uploadId}/chunks/{index}` | 上传分块 |
| GET | `/api/uploads/{uploadId}` | 查询缺块 |
| POST | `/api/uploads/{uploadId}/complete` | 合并完成 |
| GET | `/api/files/{fileId}/download` | 流式/Range 下载 |
| DELETE | `/api/files/{fileId}` | 删除文件 |

### 9.3 分块与差错重传

- 默认分块 2 MiB；
- 每块携带 `X-Chunk-SHA256`；
- 服务端先按配置概率模拟丢失，再保存；
- 保存后计算块摘要，不一致返回 422；
- `(uploadId, chunkIndex)` 幂等；
- `/complete` 检查全部分块，按顺序合并到临时文件；
- 完整 SHA-256 相同后原子移动并写数据库；
- 完成失败不产生可见文件；
- 定时清理过期上传和临时块。

差错模拟接口必须支持固定随机种子用于自动化测试，运行时默认概率 20%。

### 9.4 下载

使用流式响应并支持 Range：

- 普通下载返回 200；
- Range 下载返回 206；
- 设置 `Accept-Ranges`、`Content-Range`、`Content-Length`、`Content-Disposition`；
- 文件不存在返回 404，不关闭 WebSocket；
- 下载授权只根据服务端查询结果，不信任客户端传来的 ownerId。

---

## 10. TCP/UDP 延时实验

### 10.1 二进制报文

统一使用 Big Endian：

| 字段 | 长度 | 说明 |
| --- | --- | --- |
| magic | 4 | `0x43484154` |
| version | 1 | 1 |
| type | 1 | PING/PONG/DATA/ACK |
| requestId | 8 | 请求序号 |
| sentAtNs | 8 | 发送方单调时钟 |
| payloadLength | 4 | 负载长度 |
| payload | 可变 | 测试数据 |

TCP 必须使用 LengthFieldBasedFrameDecoder 或等价方案处理粘包/半包。UDP 报文不超过 1200 字节，避免 IP 分片。

### 10.2 服务

- `TcpEchoServer`：9001；
- `UdpEchoServer`：9002；
- Spring Boot 启动后异步启动；
- 关闭时释放 Channel 和 EventLoopGroup；
- 收到 PING 后立即返回同 requestId 的 PONG；
- 异常报文只影响当前请求。

### 10.3 测试任务 API

`POST /api/network-tests/latency`

```json
{
  "protocol": "TCP",
  "host": "192.168.1.10",
  "port": 9001,
  "samples": 30,
  "intervalMs": 100,
  "timeoutMs": 1000,
  "scenario": "LAN_IDLE"
}
```

使用 `System.nanoTime()` 测经过时间。统计平均、最小、最大、方差、标准差、P50、P95 和 UDP 丢包率。原始样本写入：

```text
runId,scenario,protocol,target,sequence,sentAtNs,receivedAtNs,rttMs,status
```

网络测试目标默认限制为本机和私有网段，避免形成任意公网扫描接口。

---

## 11. 带宽测试

### 11.1 TCP

1. 客户端建立 9001 连接；
2. 连续 5～10 秒发送 64 KiB DATA；
3. 服务器原样回弹；
4. 按实际收发字节和持续时间计算 Mbps/MiB/s；
5. 同时记录平均延时和错误数。

### 11.2 UDP

- 单报文负载控制在 1200 字节以内；
- 带序号；
- 统计发送、接收、丢失、重复、乱序；
- 吞吐量只按成功接收的有效负载计算；
- 测试结束使用结束报文交换最终统计。

### 11.3 实验步骤

每种场景测试至少 10 轮，每轮至少 30 个延时样本：

1. 同一局域网且双方空闲；
2. 客户端播放高清视频；
3. 同一 WiFi 正在大文件下载或上传；
4. TCP 与 UDP 分别执行；
5. 导出 CSV；
6. 根据真实数据分析排队、丢包和重传。

不能在代码或报告中伪造实验结果。

---

## 12. Blocking 与 NIO Selector 对比

实现两个行为相同的 TCP Echo 服务：

- 9101：`BlockingTcpEchoServer`，一个连接一个工作线程；
- 9102：`NioTcpEchoServer`，使用 Java NIO Selector 或 Netty EventLoop。

压测参数：

- 并发连接：50、100、200、500；
- 消息大小：1 KiB、16 KiB、64 KiB；
- 每档 30 秒；
- 每组重复 3 次。

输出成功请求、吞吐量、平均/P95 延时、错误率、服务端线程数、CPU 和内存。两种服务必须使用相同机器、JVM 参数、消息内容和持续时间。

---

## 13. WebRTC 信令

后端只做信令转发：

- `RTC_OFFER`：发起方发送给目标用户；
- `RTC_ANSWER`：目标用户回传；
- `RTC_ICE`：双方交换 ICE Candidate；
- 校验目标用户在线；
- 不解析 SDP 内容，不保存 P2P 消息或 P2P 文件；
- DataChannel 建立后，业务数据不经过服务器。

若使用 TURN，实际数据会经 TURN 中继，实验报告要如实说明。

---

## 14. 管理功能

ADMIN Token 可访问：

- `GET /api/admin/users`；
- `PUT /api/admin/users/{id}/enabled`；
- `PUT /api/admin/settings/error-rate`；
- `POST /api/admin/notices`。

禁用用户后关闭其在线 WebSocket。修改差错率即时生效。压力测试广播只能发送“准备通知”，客户端仍需确认，避免一条广播直接触发所有设备大流量传输。

---

## 15. 后端实施阶段

### 阶段 B0：工程、MyBatis-Plus 与数据库

完成：Spring Boot 4.1.1、Java 25、依赖、MySQL、Flyway、统一响应、全局异常、健康检查、MyBatis-Plus 配置。

验收：应用启动、Flyway 建表、Mapper 集成测试、`mvn test`、`mvn package` 通过。

### 阶段 B1：轻量登录

完成：注册、BCrypt 哈希、登录、JWT、MVC 拦截器、当前用户上下文、管理员角色。

验收：数据库无明文密码；正确密码成功；错误密码失败；过期/伪造 Token 返回 401；不含 Security Starter。

### 阶段 B2：WebSocket 聊天

完成：首帧 AUTH、在线会话、私聊、群聊、心跳、离线、消息入库、系统通知。

验收：两个账号私聊和广播；未认证连接不能发消息；断线会被清理。

### 阶段 B3：文件服务

完成：公共/私人文件、分块、摘要、差错模拟、幂等、合并、Range 下载、过期清理。

验收：20% 固定随机差错下最终摘要相同；用户 A 不能访问用户 B 私人文件；100 MiB 文件不整块进内存。

### 阶段 B4：TCP/UDP 网络实验

完成：协议编解码、Echo、延时任务、带宽任务、CSV、统计。

验收：TCP/UDP 各 30 个样本；TCP 拆包粘包测试；UDP 超时不永久阻塞。

### 阶段 B5：性能、WebRTC 与管理

完成：Blocking/NIO 对比服务、压测客户端、WebRTC 信令、管理员接口、运行时设置。

验收：输出真实 CSV；信令只转发给目标用户；配置修改无需重启。

---

## 16. 自动化测试

### 16.1 登录

- BCrypt 保存值不等于原密码；
- 正确/错误密码匹配；
- 重复用户名 409；
- JWT 签名、issuer、过期时间；
- 伪造和过期 Token 401；
- 禁用用户不能继续访问。

### 16.2 MyBatis-Plus

- UserMapper CRUD；
- 消息历史分页；
- 公共/私人文件条件查询；
- 上传分块唯一索引和幂等；
- 统计 Mapper XML 字段映射。

### 16.3 WebSocket

- 未发 AUTH 时发送聊天被拒绝；
- 两客户端认证；
- 私聊只到目标；
- 广播到所有在线用户；
- 重复连接踢旧；
- 心跳超时离线。

### 16.4 文件

- 路径穿越被拒绝；
- 用户隔离；
- 分块摘要错误；
- 重复块幂等；
- 缺块不能 complete；
- 20% 固定随机差错最终成功；
- 完整文件摘要一致；
- Range 边界正确。

### 16.5 网络

- TCP 编解码拆包/粘包；
- UDP 超时、丢包和乱序；
- 平均值、方差、P50、P95；
- Netty 启停不泄漏线程；
- Blocking/NIO 压测参数有效。

每阶段实际运行：

```bash
./mvnw test
./mvnw clean package
```

Windows 使用 `mvnw.cmd`。

---

## 17. 安全和可靠性边界

即使采用轻量方案，也必须保留：

- BCrypt，不保存明文密码；
- JWT Secret 从环境变量读取；
- Token 不进入 URL 和日志；
- 私人文件按服务端解析的用户 ID 授权；
- 文件名清洗和路径穿越防护；
- WebSocket 消息类型和长度限制；
- SQL 参数绑定；
- 上传并发和文件大小限制；
- 耗时任务超时和取消；
- Netty、线程池、临时文件的优雅清理。

不实现 Refresh Token、Token 黑名单、分布式 Session、OAuth2 和复杂权限模型。报告中注明：这是单机课程项目方案，不是生产级认证系统。

---

## 18. 可直接发给 OpenCode 的后端总提示词

```text
你只负责聊天室系统的 backend/ 和 probe-client/，不要修改 frontend/。

先完整阅读《聊天室系统-后端OpenCode实施文档.md》。技术栈固定为 Java 25、Spring Boot 4.1.1、MyBatis/MyBatis-Plus 3.5.17、MySQL、Flyway、WebSocket 和 Netty。

登录必须使用轻量方案：spring-security-crypto 的 BCryptPasswordEncoder 只负责密码哈希，Auth0 java-jwt 4.6.0 负责 Token，自定义 HandlerInterceptor 负责 REST Token，WebSocket 使用连接后的首帧 AUTH。禁止引入 spring-boot-starter-security、Spring Data JPA、OAuth2 或复杂认证框架。

本次只执行我指定的 B 阶段。开始前检查现有代码并列出将修改的文件；完成后实际运行 ./mvnw test 和 ./mvnw clean package，修复本阶段问题，更新接口文档并给出手工调用示例。不要伪造测试结果或性能数据。
```

阶段指令示例：

```text
请只执行后端文档中的阶段 B0。先完成工程、依赖、MySQL、Flyway、MyBatis-Plus、统一响应和健康检查，不提前实现登录、聊天、文件或 Netty。完成后报告启动命令、迁移结果、测试结果和剩余任务。
```

后续依次把 `B0` 替换为 `B1`、`B2`、`B3`、`B4`、`B5`。

---

## 19. 前后端联调顺序

1. 后端 B0 与前端 F0：健康检查；
2. 后端 B1 与前端 F1：注册、登录、Token；
3. 后端 B2 与前端 F2：WebSocket 聊天；
4. 后端 B3 与前端 F3：文件；
5. 后端 B4 与前端 F4：网络测试；
6. 后端 B5 与前端 F5：WebRTC、性能实验和管理功能。

任何接口变更必须先更新文档，再同时调整两端。不要让 OpenCode 在前后端分别猜测字段名。

---

## 20. 后端完成定义

- Java 25 + Spring Boot 4.1.1 可启动；
- 使用 MyBatis/MyBatis-Plus，不存在 JPA Repository；
- Flyway 可从空库初始化；
- BCrypt 和 JWT 登录可用，但没有 Security Starter；
- REST 拦截器和 WebSocket 首帧 AUTH 可用；
- 私聊、群聊、在线列表和系统通知可用；
- 公共和私人文件权限正确；
- 大文件分块上传、差错重传、流式/Range 下载可用；
- TCP/UDP 延时与带宽测试可用；
- Blocking/NIO 对比程序可输出真实 CSV；
- WebRTC 信令只中转，不保存 P2P 数据；
- 所有核心资源可优雅关闭；
- `mvn test` 与 `mvn clean package` 通过；
- 没有明文密码、硬编码 JWT Secret 和伪造实验数据。

---

## 21. 官方版本参考

- Spring Boot 4.1.1 系统要求：<https://docs.spring.io/spring-boot/system-requirements.html>
- MyBatis-Plus Spring Boot 4 快速开始：<https://baomidou.com/en/getting-started/>
- Spring Security Crypto `PasswordEncoder`：<https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/crypto/password/PasswordEncoder.html>
- Auth0 Java JWT Releases：<https://github.com/auth0/java-jwt/releases>

以 Maven 锁定和实际构建结果为准，不在同一阶段中随意升级版本。
