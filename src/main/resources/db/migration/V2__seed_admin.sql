-- ============================================================
-- V2__seed_admin.sql — 管理员种子账号
-- 用户名：admin  密码：admin123456（开发用途，见实施偏差说明）
-- 哈希由 BCryptPasswordEncoder(10) 生成并经 matches 验证
-- ============================================================

INSERT INTO users (username, display_name, password_hash, role, enabled)
SELECT 'admin', '管理员', '$2a$10$RRA8lBwKVoiqkZQ0y7fR0O9txMzKdGFi52qf/sRjob4aHIL21io3i', 'ADMIN', 1
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'admin');
