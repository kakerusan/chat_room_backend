# 聊天室系统 — RustFS 对象存储集成实施文档

> 项目：网络软件设计——聊天室系统
> 前置文档：《聊天室系统-后端OpenCode实施文档.md》第 9 节（文件服务）
> 本文档规划并记录"文件存储后端接入 RustFS"技术点的实施。
>
> **实施状态：R1–R5 已全部完成并通过验收（2026-09-10）**，落地偏差见第 13 节。

---

## 1. 背景与范围

### 1.1 范围界定

文件功能维持现有"两阶段存储转发"架构：A 分块上传 → 服务端校验/存储 → B 授权下载。
本技术点把存储后端从**本地磁盘**替换为 **RustFS（S3 兼容对象存储）**，并保留本地磁盘模式作为可切换选项（自动化测试不依赖 Docker）。

明确不在本技术点范围内：

- WebSocket 实时中转（不落盘直推）；
- 模拟 CHAP、Blocking/NIO 性能对比（另行规划）；
- 预签名 URL 直传/直下（作为可选的后续阶段，见第 11 节）。

### 1.2 必须保持不变的对外契约

- REST API 路径与请求/响应结构（`POST /api/uploads`、`PUT /api/uploads/{id}/chunks/{index}`、`GET /api/uploads/{id}`、`POST /api/uploads/{id}/complete`、`GET /api/files/{id}/download`、`DELETE /api/files/{id}`）；
- 每块 20% 差错模拟（固定种子可复现）；
- 每块 `X-Chunk-SHA256` 校验；
- `(uploadId, chunkIndex)` 幂等；
- 断点续传（查询缺块）；
- Range 下载（200/206/416）；
- 授权逻辑（JWT + 服务端查库判断 owner，不信任客户端参数）。

唯一允许的契约增量：`POST /api/uploads` 响应中新增 `chunkSize` 字段（服务端实际生效的分块大小），前端据此切块。见 5.2 节。

### 1.3 RustFS 关键事实（已核实）

| 事实 | 值 | 来源 |
| --- | --- | --- |
| Docker 镜像 | `rustfs/rustfs:latest`（Docker Hub） | docs.rustfs.com 安装文档 |
| S3 API 端口 | 容器内 9000（`RUSTFS_ADDRESS`） | 官方环境变量参考 |
| Web 控制台端口 | 容器内 9001（`RUSTFS_CONSOLE_ADDRESS`） | 同上 |
| 凭据环境变量 | `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY`（默认 `rustfsadmin`，必须改） | 同上 |
| 数据目录 | 容器内 `/data`，容器以 uid 10001 运行 | 同上 |
| Multipart Upload | 官方兼容矩阵标记 TESTED | s3-compatibility 页 |
| Range GET / 预签名 / path-style | TESTED / TESTED / 默认 | 同上 |
| 桶自动创建 | **不支持**（社区 PR 未合并）——应用启动时 headBucket/createBucket | issue #2179 |
| ACL | 不支持（禁用 canned ACL） | 同上 |
| 官方 Java 接入指引 | AWS SDK for Java v2 + `forcePathStyle(true)` + `Region.US_EAST_1` | docs.rustfs.com Java SDK 页 |

已知坑：AWS SDK v2 ≥ 2.30.0 默认对所有 PutObject 附带 CRC32 校验头，部分 S3 兼容存储会报错。构建 `S3Client` 时必须显式设置：

```java
.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
```

---

## 2. 总体设计

### 2.1 设计原则

1. **接口抽象，双实现**：抽 `StorageBackend` 接口，`LocalDiskStorageBackend`（现有逻辑原样搬移）与 `S3StorageBackend`（RustFS）并列，`chatroom.storage.type` 配置切换。
2. **DB 语义存储后端无关**：`stored_files.relative_path` 在 local 模式是磁盘相对路径，在 S3 模式就是对象 key，两者使用**同一套命名规则**（`public/...`、`users/{id}/...`），表结构不感知后端。
3. **分块 ↔ S3 分片一一映射**：1 个 chunk = 1 个 UploadPart，复用现有会话/分块表做 S3 multipart 的账本，不引入新状态存储。
4. **REST 层零改动**：Controller 与 UploadService 流程不动，仅把对 `StorageService` 的调用改为 `StorageBackend`。

### 2.2 架构图

```text
前端 ──HTTP──> UploadController/FileController
                    │
              UploadService / FileService        （业务规则不变：差错模拟、sha 校验、幂等、授权）
                    │
             StorageBackend (interface)
              ├─ LocalDiskStorageBackend   type=local（默认，测试用）
              └─ S3StorageBackend          type=s3
                    │  AWS SDK v2 (path-style, checksum WHEN_REQUIRED)
                    ▼
              RustFS 容器 :9000  ── /data 卷（具名卷）
                    │
              MySQL（upload_sessions / upload_chunks / stored_files，仅加 2 个可空列）
```

### 2.3 StorageBackend 接口（实施时按此签名）

```java
public interface StorageBackend {

    /** 创建后端上传上下文。返回 backendUploadId（S3: CreateMultipartUpload 的 uploadId；local: null） */
    String initUpload(String uploadId, String scope, Long ownerId, int totalChunks);

    /** 写入一个分块，返回该块后端标识（S3: part ETag；local: null）。data 已通过业务层 sha 校验 */
    String putChunk(String uploadId, String backendUploadId, int chunkIndex, byte[] data);

    /**
     * 完成上传，返回最终存储位置（对象 key / 磁盘相对路径）与完整文件 SHA-256。
     * S3 实现：CompleteMultipartUpload 后 GetObject 回流一次计算 SHA-256。
     * local 实现：合并时流式计算 SHA-256（现状逻辑）。
     */
    CompletedUpload completeUpload(String uploadId, String backendUploadId,
            String scope, Long ownerId, String originalName, int totalChunks, String targetKey);

    /** 放弃上传（S3: AbortMultipartUpload；local: 删除 temp 目录） */
    void abortUpload(String uploadId, String backendUploadId);

    record CompletedUpload(String key, String sha256Hex) {}

    /** 打开读取流；start/end 为 null 表示全量（S3: Range GET；local: skip） */
    InputStream openStream(String key, Long start, Long end);

    long size(String key);

    boolean exists(String key);

    void delete(String key);
}
```

`targetKey`（对象 key / 相对路径）由业务层按现有规则生成（`public/` 或 `users/{ownerId}/` + 时间戳-随机串-净化扩展名），两种后端共用，保证 DB 语义一致。

---

## 3. 分块 ↔ S3 Multipart 映射规则

| 规则 | 说明 |
| --- | --- |
| partNumber | `chunkIndex + 1`（S3 分片号从 1 开始，本地 chunkIndex 从 0 开始，边界必须换算） |
| 分片数上限 | S3 单次 multipart 最多 10000 片；5 MiB × 10000 = 50 GiB，覆盖课程项目规模（multipart 上限 2GB） |
| 最小分片 | 除最后一片外每片 ≥ 5 MiB（S3 硬性规则）→ 默认 chunkSize 从 2 MiB 调整为 5 MiB |
| 乱序 | S3 天然支持乱序 UploadPart（客户端重传/并发上传场景） |
| 幂等重传 | 同一 partNumber 重复 UploadPart = 覆盖旧数据，安全；数据在业务层已按 sha256 校验一致。DB 唯一索引 + DuplicateKeyException 兜底不变 |
| Complete | 从 `upload_chunks` 按 `chunk_index` 升序取 `(partNumber, etag)` 组装 `CompleteMultipartUploadRequest`。分片 ETag 存入 `upload_chunks.etag` 新列 |
| 断点续传 | 不变：`GET /api/uploads/{id}` 返回已上传块号；缺块照常 PUT，S3 端 multipart 上下文仍在 |

**注意**：multipart ETag **不是**文件级哈希（`md5(parts...)-N` 形式），不能用于完整文件校验，必须走回流计算（见第 6 节）。

---

## 4. 配置与依赖

### 4.1 pom.xml 增量

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <!-- 版本交由 Spring Boot BOM 管理；若无 BOM 管理则固定 2.30.x+ -->
</dependency>
```

### 4.2 application.yml 增量

```yaml
chatroom:
  storage:
    type: local                      # local | s3（默认 local：测试与无 Docker 环境可用）
    endpoint: http://localhost:9000  # RustFS S3 API
    access-key: chatroom-dev
    secret-key: chatroom-dev-secret
    bucket: chatroom-files
    region: us-east-1
```

`ChatroomProperties` 新增嵌套 `Storage` 类对应上述字段。密钥支持环境变量占位：`access-key: ${RUSTFS_ACCESS_KEY:chatroom-dev}`。

### 4.3 S3Client Bean（config/S3ClientConfig.java）

```java
@Bean
@ConditionalOnProperty(name = "chatroom.storage.type", havingValue = "s3")
public S3Client s3Client(ChatroomProperties props) {
    return S3Client.builder()
            .endpointOverride(URI.create(props.getStorage().getEndpoint()))
            .region(Region.of(props.getStorage().getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)))
            .forcePathStyle(true)     // RustFS path-style 必需
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)  // 规避 CRC32 兼容问题
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build();
}
```

两个 Backend 均用 `@ConditionalOnProperty(name = "chatroom.storage.type", ...)` 装配，`type=local` 为缺省，保证**不配任何新配置时行为与现状完全一致**。

### 4.4 docker-compose.yml 增量

```yaml
  rustfs:
    image: rustfs/rustfs:latest      # 本地已保存该镜像
    container_name: chatroom-rustfs
    ports:
      - "9000:9000"   # S3 API（宿主机 9000 空闲，经核对未占用）
      - "9120:9001"   # Web 控制台（宿主机 9001 被 TCP echo 占用；9100 落在 Hyper-V 排除区 9015-9114，实际用 9120）
    environment:
      RUSTFS_ACCESS_KEY: chatroom-dev
      RUSTFS_SECRET_KEY: chatroom-dev-secret
      RUSTFS_ADDRESS: ":9000"
      RUSTFS_CONSOLE_ADDRESS: ":9001"
      RUSTFS_CONSOLE_ENABLE: "true"
    volumes:
      - chatroom-rustfs-data:/data   # 具名卷：规避 uid 10001 宿主目录属主问题（Windows bind-mount 免 chown）
    # 与 mysql 无依赖关系，独立服务；实现阶段验证镜像内是否有 wget/curl 再决定 healthcheck 写法

volumes:
  chatroom-rustfs-data:
```

---

## 5. 业务流程变更

### 5.1 上传会话创建（UploadService.createSession）

新增校验与字段：

- `totalChunks > 1` 时强制 `chunkSize >= 5 MiB`（local 模式同样执行，保持两后端行为一致）；`totalChunks == 1` 不限。
- 服务端默认 chunkSize 由 2 MiB 改为 **5 MiB**。
- 调用 `backend.initUpload(...)`，将返回的 `backendUploadId` 存入 `upload_sessions.backend_upload_id`。

### 5.2 响应契约增量

`POST /api/uploads` 响应在现有 `uploadId`、`uploadedChunks` 基础上新增：

```json
{ "uploadId": "...", "uploadedChunks": [], "chunkSize": 5242880 }
```

前端必须以响应中的 `chunkSize` 为准切块（防客户端未传 chunkSize 时本地默认值与服务端不一致）。向后兼容：新增字段不破坏现有前端。

### 5.3 分块上传（uploadChunk）

顺序保持：`差错模拟 → sha256 校验 → 幂等检查 → backend.putChunk → 写 upload_chunks（含 etag）`。

- S3 模式：`putChunk` 内部执行 `UploadPartRequest(bucket, key 占位不需最终 key, uploadId=backendUploadId, partNumber=index+1)`，返回 ETag 存库。
- local 模式：写 `temp/{uploadId}/{index}.part`，etag 存 null。
- 幂等分支（块已存在）直接 return，不触达后端——现状不变。

### 5.4 完成（complete）

```text
1. 校验块齐全（uploaded.size == totalChunks）
2. status = MERGING（事务外，现状不变）
3. 生成 targetKey（现有 randomStoredPath 规则，后端无关）
4. backend.completeUpload(...)：
   S3:  按 chunk_index 升序组装 PartList → CompleteMultipartUpload
        → GetObject 全量回流，流式计算 SHA-256（单次额外读，本地回环约 2~4 s/GB，可接受）
   local: 顺序合并到 .merging 临时文件，边合并边算 SHA-256（现状）
5. 客户端声明了 fileSha256 且不一致 → backend.delete(targetKey) + 会话回 INIT + 422（现状语义）
6. 写 stored_files（relative_path = targetKey，两模式语义统一）→ status=COMPLETED → 清理（S3: abort 已不需要；local: 清 temp 目录）
```

`fileSha256` 客户端未声明时跳过比对——与现状一致。

### 5.5 下载（FileController.download）

控制器现有 Range 解析逻辑保留（bytes=start-end / suffix / 越界 416），仅把"openStream + skip"改为：

```java
InputStream in = backend.openStream(file.getRelativePath(), start, end); // 全量时传 null
```

- S3 实现：`GetObjectRequest.range("bytes=" + start + "-" + end)`，RustFS 返回 206 流。
- local 实现：`Files.newInputStream` + 调用方 skip（维持现状，接口内实现 skip 逻辑）。
- 授权流程不变：先 `authorizeDownload`（JWT → 查库 → owner 校验），再触达存储。

### 5.6 删除

`FileService.deleteFile` 授权不变，`storage.delete(relativePath)` → `backend.delete(key)`。

---

## 6. 状态机与异常恢复

会话状态机保持 `INIT → MERGING → COMPLETED / EXPIRED`。

**应用启动（s3 模式）恢复逻辑**（`S3StorageBackend` 初始化或独立 `StorageRecoveryRunner`）：

1. `headBucket` → 不存在则 `createBucket`（RustFS 无自动建桶，必须应用侧兜底）；
2. 查询所有 `status IN (INIT, MERGING)` 的会话：
   - 有 `backend_upload_id` → `abortMultipartUpload`（否则已上传分片在 RustFS 侧永久占存储）；
   - 标记 `EXPIRED`，客户端重新发起上传。
3. 简化取舍：不做 ListParts 对账、不续用断点（跨重启续传 S3 端可行但状态机复杂度不划算，课程项目明确"重启即作废"）。

**已知限制（报告如实注明）**：若应用在 CompleteMultipartUpload 成功后、stored_files 落库前崩溃，会留下无主对象；启动恢复按会话状态标记 EXPIRED 但不删除该孤儿对象（无法从 DB 反查 key）。发生窗口极窄，接受。

**每小时过期清理任务**（`evictExpiredSessions`）扩展：过期会话若有 `backend_upload_id` → `abortUpload`；temp 对象清理逻辑两模式各自实现。

---

## 7. 数据库迁移（V3__storage_backend.sql）

下一个迁移版本为 **V3**（V1 建表、V2 种子管理员已存在）：

```sql
-- S3 multipart 分片 ETag（local 模式为 NULL）
ALTER TABLE upload_chunks
    ADD COLUMN etag VARCHAR(128) NULL COMMENT 'S3 分块 ETag（本地磁盘模式为空）' AFTER chunk_sha256;

-- 后端上传上下文标识（S3 multipart uploadId；local 模式为 NULL）
ALTER TABLE upload_sessions
    ADD COLUMN backend_upload_id VARCHAR(128) NULL COMMENT '后端上传标识（S3 multipart uploadId）' AFTER status;
```

两列均可空 → local 模式零影响；实体类 `UploadChunkEntity`、`UploadSessionEntity` 各加一个 `@TableField`。

---

## 8. 代码结构规划

```text
file/storage/
├─ StorageBackend.java            新增：接口 + CompletedUpload record
├─ LocalDiskStorageBackend.java   新增：由现 StorageService 平移（resolve/writeChunk/mergeChunks/atomicMove/...）
├─ S3StorageBackend.java          新增：multipart 生命周期 + Range GET + bucket 兜底 + 启动恢复
├─ StorageService.java            删除或退化为包内工具（实施时决定，避免双入口）
config/
├─ S3ClientConfig.java            新增：S3Client Bean（s3 模式条件装配）
ChatroomProperties.java           修改：+ Storage 嵌套配置类
```

涉及修改的现有文件（预计）：`ChatroomProperties`、`UploadService`、`UploadController`（complete 响应无改动，create 响应加 chunkSize）、`FileController`（openStream 签名）、`FileService`（storage() 暴露方式）、两个实体类、`application.yml`、`docker-compose.yml`、`pom.xml`。

**重构纪律**：Local 模式行为必须逐字节等价（同样的临时目录结构、同样的合并与原子移动语义），现有全部测试不动即绿，作为重构正确性的验收手段。

---

## 9. 测试策略

| 层级 | 内容 | 运行条件 |
| --- | --- | --- |
| 单元/切片测试（现有） | type=local 下全量回归：路径穿越、用户隔离、分块摘要错误、重复块幂等、缺块拒 complete、20% 固定种子差错最终成功、Range 边界 | 无 Docker，`mvnw test` 必须全绿 |
| S3Backend 集成测试（新增） | 建 bucket 兜底、putChunk/etag、乱序分块 complete、幂等重传、abort、Range openStream 边界、sha 回流比对 | 需 RustFS 容器运行；用 `@EnabledIfEnvironmentVariable(RUSTFS_IT=1)` 或 JUnit Tag 隔离，默认跳过 |
| 手工端到端 | curl 全流程：注册登录 → 建会话 → 分块（制造若干失败触发差错模拟）→ complete → 列表 → 全量下载 → Range 下载 → 删除；控制台 http://localhost:9100 目视核对桶/对象 | docker compose up rustfs |

不伪造测试与性能数据；S3 集成测试不绿不得宣称该阶段完成（与主文档第 16/20 节要求一致）。

---

## 10. 实施阶段划分

### 阶段 R1：容器与客户端基座
docker-compose 增加 rustfs 服务；pom 引入 AWS SDK v2；S3ClientConfig + 配置项；`type` 缺省 local 保证现状零影响。
**验收**：`docker compose up -d rustfs` 后控制台可登录；`mvnw test` 全绿（未触碰业务代码）。

### 阶段 R2：存储层抽象（纯重构）
抽 StorageBackend 接口；StorageService 平移为 LocalDiskStorageBackend；UploadService/FileService 改依赖接口；local 装配为缺省。
**验收**：不新增任何功能；现有测试 100% 通过；`mvnw clean package` 通过。

### 阶段 R3：V3 迁移与 S3 写路径
Flyway V3 两个可空列；S3StorageBackend 的 initUpload/putChunk/abortUpload；createSession 强制 5 MiB 校验与 chunkSize 响应字段；过期清理任务扩展 abort。
**验收**：S3 集成测试（putChunk/etag/乱序/幂等/abort）通过；local 模式回归通过。

### 阶段 R4：完成与校验、恢复
completeUpload（Complete + 回流 SHA-256 + 不一致删除）；启动恢复（bucket 兜底 + 悬挂 multipart abort + EXPIRED）。
**验收**：声明错误 sha → 422 且对象被删；重启应用 → 未完成会话 EXPIRED 且 RustFS 侧无悬挂分片（控制台/对象列表核对）。

### 阶段 R5：读路径与端到端
openStream Range 透传（S3）+ skip 兼容（local）；FileController 切换；删除/exists/size；手工端到端全流程；更新主实施文档第 9 节与接口文档。
**验收**：第 9 节测试矩阵全部通过；接口文档含 chunkSize 新字段说明与 curl 示例。

每阶段收尾实际执行 `./mvnw test` 与 `./mvnw clean package`（Windows 用 `mvnw.cmd`），失败当场修复后再进入下一阶段。

---

## 11. 可选后续（本期不做）

- **预签名 URL**：`GET /api/files/{id}/download-url` 签发短时效预签名 GET，浏览器直连 RustFS，降低应用中转带宽。RustFS 兼容矩阵已确认支持。做的话需评估授权语义（签名 URL 一旦签出即绕过服务端授权，需短时效 + 只读）。
- **分块并发上传**：前端并行 PUT 多块（接口天然支持，S3 乱序分片无障碍），提速明显但属前端改动。
- **断点跨重启续传**：ListParts 对账 + 恢复 INIT 会话，当前明确放弃（见第 6 节）。

---

## 12. 风险与对策清单

| # | 风险 | 对策 |
| --- | --- | --- |
| 1 | AWS SDK v2 ≥ 2.30.0 CRC32 校验头导致 RustFS 拒绝请求 | S3Client 强制 WHEN_REQUIRED 两项设置（4.3 节），集成测试首个用例即覆盖 putChunk |
| 2 | partNumber 1-based / chunkIndex 0-based 换算错位 | completeUpload 只从 DB `chunk_index` 升序推导 partNumber=index+1；集成测试含乱序块 |
| 3 | chunkSize < 5 MiB 被 S3 拒（非最后分片） | createSession 强校验 + 响应回传 chunkSize；前端以响应为准 |
| 4 | 宿主端口冲突（9001 已被 TCP echo 占用） | 控制台映射 9120（9100 在 Hyper-V 排除区 9015–9114 内，实测后调整）；S3 API 9000 已核对空闲 |
| 5 | Windows bind-mount 属主（uid 10001）问题 | 具名卷 `chatroom-rustfs-data`，不用宿主目录挂载 |
| 6 | 悬挂 multipart 占用存储 | 启动恢复 + 每小时过期任务双保险均调 abort |
| 7 | 镜像内无 curl/wget 导致 healthcheck 失败 | 未配置 healthcheck，靠应用侧 ensureBucket 失败快速报错 |
| 8 | Complete 后落库前崩溃产生孤儿对象 | 接受并文档化（第 6 节已知限制），实验报告如实说明 |

---

## 13. 实施记录与落地偏差（2026-09-10）

全部 5 个阶段一次通过，关键落地偏差与最终形态：

### 13.1 与规划的设计差异

| 规划 | 实际落地 | 原因 |
| --- | --- | --- |
| 控制台映射宿主 9100 | **9120** | 9100 落在 Windows Hyper-V 动态端口排除区（9015–9114），`docker compose up` 实测 bind 失败 |
| `upload_sessions`/`upload_chunks` 加 2 列 | **3 列**（etag、backend_upload_id、**target_key**） | S3 `CreateMultipartUpload` 必须在会话创建时就知道最终 key；key 提前生成并存库，两种后端共用 |
| completeUpload 按 scope/owner 生成路径 | 签名改为携带 **parts 列表**（chunkIndex+etag） | `CompleteMultipartUpload` 需要全部分片的 ETag，账本在 DB；不依赖 ListParts（RustFS 兼容矩阵未保证该接口） |
| openStream(key) + skip | openStream(key, **start, end**) Range 直通 | local 实现从 InputStream.skip（有短读歧义）改为 FileChannel.position + 限长流；S3 实现直接 Range GET，旧代码的 skip 路径顺带修正 |
| 旧 StorageService 保留 | **删除**，逻辑平移至 LocalDiskStorageBackend | 避免双入口；测试同步改写并新增 Range/completeUpload/key 规则用例 |

### 13.2 新增组件清单

```text
file/storage/StorageBackend.java            接口 + UploadedPart/CompletedUpload record + newTargetKey 静态工厂
file/storage/LocalDiskStorageBackend.java   本地实现（@ConditionalOnProperty local，matchIfMissing=true 缺省）
file/storage/S3StorageBackend.java          S3 实现（multipart 生命周期、Range GET、回流 SHA-256、ensureBucket）
file/service/UploadSessionRecovery.java     启动恢复 Runner（悬挂会话 abort + EXPIRED）
config/S3ClientConfig.java                  S3Client 条件装配（path-style + WHEN_REQUIRED 校验）
db/migration/V3__storage_backend.sql        3 个可空列
```

修改：`ChatroomProperties`（+Storage 嵌套类）、`UploadService`、`UploadController`（5 MiB 默认 + chunkSize 回传）、`FileService`、`FileController`、两个实体类、`application.yml`、`docker-compose.yml`、`pom.xml`（AWS SDK v2 2.39.6）。

### 13.3 验收结果（真实执行）

| 验收项 | 结果 |
| --- | --- |
| `mvnw test`（local 模式，IT 默认跳过） | **48/48 通过**，含新增 Range/幂等/路径穿越/key 规则用例 |
| `mvnw clean package` | BUILD SUCCESS |
| S3StorageBackendIT（`RUSTFS_IT=1`，直连容器） | **3/3 通过**：乱序分片 multipart 全流程 + 回流 SHA、同 partNumber 幂等重传覆盖、abort 后无残留对象 |
| 应用 s3 模式启动 | Flyway V3 迁移成功；`S3 bucket ready: chatroom-files`；bucket 自动兜底生效 |
| 端到端（HTTP 全链路，8 MiB 双块文件） | **10/10 通过**：登录 → 建会话（chunkSize=5242880 回传）→ 分块上传（20% 差错模拟下重试）→ complete（SHA-256 一致）→ 列表 → 全量下载（摘要一致）→ Range 206（bytes=100-199，100 字节内容逐字节验证）→ 后缀 Range → 删除 → 删后 404 |
| 崩溃恢复 | 制造悬挂 multipart 会话（INIT + 1 块）→ kill 进程 → 重启 → `Recovered dangling session -> EXPIRED`，DB 状态确认 EXPIRED |

运行环境实测：RustFS `1.0.0-alpha.83`（本地镜像），MySQL 26.7 容器（3307），JDK 21（项目实际 Java 21，规划时写的 25 为文档笔误，pom `java.version=21` 未改动）。

### 13.4 手工运行指引

```powershell
# 1. 起存储（控制台 http://localhost:9120，账密 chatroom-dev / chatroom-dev-secret）
docker compose up -d rustfs mysql

# 2. s3 模式启动应用
$env:CHATROOM_STORAGE_TYPE="s3"; java -jar target\chatBackend-0.0.1-SNAPSHOT.jar

# 3. S3 集成测试（默认跳过）
$env:RUSTFS_IT="1"; .\mvnw.cmd test -Dtest=S3StorageBackendIT
```

切换回本地磁盘模式：不设置 `CHATROOM_STORAGE_TYPE`（缺省 local）即可，行为与接入前完全一致。
