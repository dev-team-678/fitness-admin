-- V6__remove_ai_config_from_sys_config.sql
-- AI 配置已迁移至 Nacos (dataId=fitness-admin-ai.yaml, group=DEFAULT_GROUP, namespace=fitness-admin)。
-- 同步删除 sys_config 表中残留的 ai.* 旧数据,释放空间,避免与 Nacos 真值不一致导致误读。

DELETE FROM `sys_config` WHERE `config_key` LIKE 'ai.%';
