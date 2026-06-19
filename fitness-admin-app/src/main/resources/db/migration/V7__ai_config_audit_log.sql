-- V7__ai_config_audit_log.sql
-- AI 配置变更审计日志
-- 记录谁在什么时间改了 Nacos 中 ai.* 的哪个字段(值脱敏为 ***),
-- 用于:
--   1. 追溯误操作(谁把 api-key 改坏了)
--   2. 合规审计(满足等保对配置变更的可追溯要求)
--   3. 配合 Nacos 历史版本(回滚时知道原始值是哪一版)
--
-- 字段:
--   - data_id:    Nacos dataId,扩展可支持多套配置(目前只 fitness-admin-ai.yaml)
--   - field:      字段名(ai.api-key → apiKey)
--   - op:         create/update/delete(目前只有 update,预留)
--   - operator:   操作人(从 SecurityUtil 拿,登录失败时为 system)
--   - created_at: 写入时间

CREATE TABLE `ai_config_audit_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `data_id` VARCHAR(128) NOT NULL COMMENT 'Nacos dataId',
  `field` VARCHAR(64) NOT NULL COMMENT '变更字段(ai.api-key → apiKey)',
  `op` VARCHAR(16) NOT NULL DEFAULT 'update' COMMENT '操作类型: create/update/delete',
  `old_value_masked` VARCHAR(64) DEFAULT NULL COMMENT '旧值(脱敏:sk-***7qWS)',
  `new_value_masked` VARCHAR(64) DEFAULT NULL COMMENT '新值(脱敏)',
  `operator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '操作人ID(对应 admin_user.id)',
  `operator_name` VARCHAR(64) DEFAULT NULL COMMENT '操作人登录名',
  `client_ip` VARCHAR(64) DEFAULT NULL COMMENT '客户端IP',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_data_field` (`data_id`, `field`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_operator` (`operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 配置变更审计日志';