package com.fitness.admin.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 配置变更审计日志。
 *
 * <p>由 {@link com.fitness.admin.system.config.NacosConfigPublisher#publishAiConfig}
 * 在推送 Nacos 前写入,记录 old/new 脱敏值与操作人。
 */
@Data
@TableName("ai_config_audit_log")
public class AiConfigAuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String dataId;
    private String field;
    private String op;
    private String oldValueMasked;
    private String newValueMasked;
    /** Key SHA-256 前 8 位 (2026-06-20 P1-6) */
    private String keyFingerprint;
    private Long operatorId;
    private String operatorName;
    private String clientIp;
    private LocalDateTime createdAt;
}