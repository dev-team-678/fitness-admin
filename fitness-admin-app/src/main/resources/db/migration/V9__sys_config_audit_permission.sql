-- V9__sys_config_audit_permission.sql
-- AI 配置审计日志菜单权限(2026-06-20)
-- 配套: 后台「系统设置 → AI 配置审计日志」菜单
-- 权限存储走 admin_role.permissions JSON,不存在 sys_permission 表,
-- 故此处不再 INSERT;由增量 SQL 2026-06-20_incremental.sql 负责绑定到 ai_ops 角色。
-- 留此空迁移占位,保证 Flyway 版本号连续(已部署 V8 的库会触发 V9,避免 V10 跳号)。
SELECT 1;
