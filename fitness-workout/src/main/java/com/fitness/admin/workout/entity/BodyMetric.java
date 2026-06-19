package com.fitness.admin.workout.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fitness.admin.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("body_metric")
public class BodyMetric extends BaseEntity {

    private Long userId;
    private LocalDate recordDate;
    private BigDecimal weightKg;
    private BigDecimal bodyFatPct;
    private BigDecimal muscleMassKg;
    private BigDecimal bmi;
    private BigDecimal chestCm;
    private BigDecimal waistCm;
    private BigDecimal hipCm;
    private BigDecimal leftArmCm;
    private BigDecimal rightArmCm;
    private BigDecimal leftThighCm;
    private BigDecimal rightThighCm;
    private String note;

    /** 是否异常: 0-正常 1-异常(待核查) */
    private Integer isAbnormal;

    /** 异常备注/处理说明 */
    private String abnormalNote;

    /** 异常标记时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime abnormalMarkedAt;

    /** 标记人(关联 admin_user.id) */
    private Long abnormalMarkedBy;
}
