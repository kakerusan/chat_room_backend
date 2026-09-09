-- 非 Docker 部署时手动执行（Docker 方式由 compose 环境变量自动完成）：
-- mysql -u root -p < db/init.sql

CREATE DATABASE IF NOT EXISTS chatroom_lab
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'chatroom'@'localhost' IDENTIFIED BY 'chatroom123';
CREATE USER IF NOT EXISTS 'chatroom'@'%' IDENTIFIED BY 'chatroom123';
GRANT ALL PRIVILEGES ON chatroom_lab.* TO 'chatroom'@'localhost';
GRANT ALL PRIVILEGES ON chatroom_lab.* TO 'chatroom'@'%';
FLUSH PRIVILEGES;

-- 表结构由 Flyway 迁移自动创建（src/main/resources/db/migration/V1__init.sql），
-- 该文件即完整建表语句，无需手动执行。
