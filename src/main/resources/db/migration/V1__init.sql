-- ============================================================
-- V1__init.sql — 聊天室系统核心表结构
-- 依据：doc/聊天室系统-后端OpenCode实施文档.md 5.1 节
-- 目标库：MySQL 8.x / utf8mb4 / InnoDB
-- ============================================================

-- 用户表
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(50)  NOT NULL                COMMENT '登录账号',
    display_name  VARCHAR(50)  NOT NULL                COMMENT '显示名称',
    password_hash VARCHAR(100) NOT NULL                COMMENT 'BCrypt 哈希',
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT 'USER/ADMIN',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否可登录',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- 聊天消息表（receiver_id 群聊时为空；chat_type: PRIVATE/BROADCAST/P2P）
CREATE TABLE chat_messages (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    sender_id   BIGINT      NOT NULL                COMMENT '发送者用户 ID',
    receiver_id BIGINT      NULL                    COMMENT '接收者用户 ID（群聊为空）',
    chat_type   VARCHAR(20) NOT NULL                COMMENT 'PRIVATE/BROADCAST/P2P',
    content     TEXT        NOT NULL                COMMENT '消息内容',
    text_color  VARCHAR(16) NULL                    COMMENT '文本颜色（可选）',
    sent_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发送时间',
    PRIMARY KEY (id),
    KEY idx_msg_sender (sender_id),
    KEY idx_msg_receiver (receiver_id),
    KEY idx_msg_sent_at (sent_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '聊天消息表';

-- 已存储文件表（公共文件 owner_id 可为空）
CREATE TABLE stored_files (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    owner_id      BIGINT       NULL                    COMMENT '所有者用户 ID（公共文件为空）',
    scope         VARCHAR(10)  NOT NULL                COMMENT 'PUBLIC/PRIVATE',
    original_name VARCHAR(255) NOT NULL                COMMENT '原始文件名（仅展示与下载头）',
    stored_name   VARCHAR(255) NOT NULL                COMMENT '服务端随机生成存储名',
    relative_path VARCHAR(512) NOT NULL                COMMENT '相对 storage root 的路径',
    size_bytes    BIGINT       NOT NULL                COMMENT '文件字节数',
    sha256        CHAR(64)     NOT NULL                COMMENT '完整文件 SHA-256',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_file_owner_scope (owner_id, scope)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '已存储文件表';

-- 上传会话表
CREATE TABLE upload_sessions (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    upload_id     VARCHAR(64)  NOT NULL                COMMENT '上传会话标识（UUID）',
    owner_id      BIGINT       NOT NULL                COMMENT '发起用户 ID',
    scope         VARCHAR(10)  NOT NULL                COMMENT 'PUBLIC/PRIVATE',
    file_name     VARCHAR(255) NOT NULL                COMMENT '原始文件名',
    file_size     BIGINT       NOT NULL                COMMENT '总字节数',
    chunk_size    INT          NOT NULL                COMMENT '分块大小（字节）',
    total_chunks  INT          NOT NULL                COMMENT '总块数',
    file_sha256   CHAR(64)     NULL                    COMMENT '完整文件 SHA-256（客户端提供）',
    status        VARCHAR(20)  NOT NULL DEFAULT 'INIT' COMMENT 'INIT/MERGING/COMPLETED/EXPIRED',
    expire_at     DATETIME(3)  NOT NULL                COMMENT '过期时间',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_upload_id (upload_id),
    KEY idx_upload_expire (expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '上传会话表';

-- 上传分块表（(upload_id, chunk_index) 唯一约束支撑幂等）
CREATE TABLE upload_chunks (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    upload_id    VARCHAR(64) NOT NULL                COMMENT '所属上传会话',
    chunk_index  INT         NOT NULL                COMMENT '分块序号（从 0 开始）',
    chunk_size   INT         NOT NULL                COMMENT '分块字节数',
    chunk_sha256 CHAR(64)    NOT NULL                COMMENT '分块 SHA-256',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_upload_chunk (upload_id, chunk_index)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '上传分块表';

-- 网络测试运行记录表（原始样本写 CSV，本表保存汇总统计）
CREATE TABLE network_test_runs (
    id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    test_type         VARCHAR(20)   NOT NULL                COMMENT 'LATENCY/BANDWIDTH/BENCHMARK',
    protocol          VARCHAR(10)   NOT NULL                COMMENT 'TCP/UDP',
    target_host       VARCHAR(64)   NOT NULL                COMMENT '目标主机',
    target_port       INT           NOT NULL                COMMENT '目标端口',
    params            VARCHAR(1024) NULL                    COMMENT '测试参数 JSON',
    started_at        DATETIME(3)   NOT NULL                COMMENT '开始时间',
    finished_at       DATETIME(3)   NULL                    COMMENT '结束时间',
    stat_min_ms       DOUBLE        NULL                    COMMENT '最小延时(ms)',
    stat_max_ms       DOUBLE        NULL                    COMMENT '最大延时(ms)',
    stat_avg_ms       DOUBLE        NULL                    COMMENT '平均延时(ms)',
    stat_variance     DOUBLE        NULL                    COMMENT '方差',
    stat_stddev       DOUBLE        NULL                    COMMENT '标准差',
    stat_p50_ms       DOUBLE        NULL                    COMMENT 'P50 延时(ms)',
    stat_p95_ms       DOUBLE        NULL                    COMMENT 'P95 延时(ms)',
    throughput_mbps   DOUBLE        NULL                    COMMENT '吞吐量(Mbps)',
    packet_loss_rate  DOUBLE        NULL                    COMMENT '丢包率(0-1)',
    error_count       INT           NULL                    COMMENT '错误数',
    scenario_name     VARCHAR(50)   NULL                    COMMENT '场景名称',
    csv_path          VARCHAR(512)  NULL                    COMMENT '原始样本 CSV 路径',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '网络测试运行记录表';
