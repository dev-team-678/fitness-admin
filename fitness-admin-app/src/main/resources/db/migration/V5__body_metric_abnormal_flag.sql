-- Flyway V5: 体测数据异常标记字段 (2026-06-19)
-- 影响: body_metric 表新增 is_abnormal / abnormal_note / abnormal_marked_at / abnormal_marked_by
-- 用途: 支持 /body-metric/{id}/abnormal 接口(管理员标记某条记录为异常,后续人工核查)
-- 幂等: 复用 V4 的 add_column_if_not_exists 存储过程模式,确保重复执行不报错

-- 工具:如果列不存在则添加
DROP PROCEDURE IF EXISTS add_column_if_not_exists;
CREATE PROCEDURE add_column_if_not_exists(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_def TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
        LIMIT 1
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_def);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END;

-- 工具:如果索引不存在则创建
DROP PROCEDURE IF EXISTS add_index_if_not_exists;
CREATE PROCEDURE add_index_if_not_exists(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_cols VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND INDEX_NAME = p_index
        LIMIT 1
    ) THEN
        SET @sql = CONCAT('CREATE INDEX `', p_index, '` ON `', p_table, '` (', p_cols, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END;

-- 1. body_metric 异常标记相关列
CALL add_column_if_not_exists('body_metric', 'is_abnormal', "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否异常: 0-正常 1-异常(待核查)' AFTER `note`");
CALL add_column_if_not_exists('body_metric', 'abnormal_note', "VARCHAR(256) DEFAULT NULL COMMENT '异常备注/处理说明' AFTER `is_abnormal`");
CALL add_column_if_not_exists('body_metric', 'abnormal_marked_at', "DATETIME DEFAULT NULL COMMENT '异常标记时间' AFTER `abnormal_note`");
CALL add_column_if_not_exists('body_metric', 'abnormal_marked_by', "BIGINT UNSIGNED DEFAULT NULL COMMENT '标记人(关联 admin_user.id)' AFTER `abnormal_marked_at`");

-- 2. 索引:管理员快速筛异常记录
CALL add_index_if_not_exists('body_metric', 'idx_abnormal', '`is_abnormal`, `record_date`');

-- 清理临时存储过程
DROP PROCEDURE IF EXISTS add_column_if_not_exists;
DROP PROCEDURE IF EXISTS add_index_if_not_exists;