-- V8__ai_config_audit_log_key_fingerprint.sql
-- AI 配置审计日志新增 key_fingerprint 字段 (2026-06-20 P1-6)
-- 用于识别"哪个 key"被改了,不暴露 key 本身。
-- 生成方式: SHA-256(newValue).substring(0, 8),只对 SECRET_FIELDS(apiKey / embeddingApiKey / qdrantApiKey) 写入。
-- 仅追加字段,不影响已有数据(已有记录 key_fingerprint 为 NULL)。

ALTER TABLE `ai_config_audit_log`
  ADD COLUMN `key_fingerprint` VARCHAR(16) DEFAULT NULL
    COMMENT 'Key SHA-256 前 8 位(用于识别"哪个 key",不暴露 key 本身)'
    AFTER `new_value_masked`,
  ADD KEY `idx_fingerprint` (`data_id`, `key_fingerprint`);
