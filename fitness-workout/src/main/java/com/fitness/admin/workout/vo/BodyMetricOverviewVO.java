package com.fitness.admin.workout.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class BodyMetricOverviewVO {

    /** 区间内总记录数 */
    private Long totalRecords;

    /** 涉及用户数 */
    private Long userCount;

    /** 异常记录数 */
    private Long abnormalCount;

    /** 最新一条记录 */
    private Latest latest;

    /** 体重/BMI 趋势点(按日期升序,空日期跳过) */
    private List<TrendPoint> weightTrend;

    /** 字段平均(用于概览卡片) */
    private AvgStats avg;

    @Data
    public static class Latest {
        private Long id;
        private Long userId;
        private LocalDate recordDate;
        private BigDecimal weightKg;
        private BigDecimal bodyFatPct;
        private BigDecimal bmi;
    }

    @Data
    public static class TrendPoint {
        private LocalDate recordDate;
        private BigDecimal weightKg;
        private BigDecimal bmi;
    }

    @Data
    public static class AvgStats {
        private BigDecimal weightKg;
        private BigDecimal bodyFatPct;
        private BigDecimal bmi;
    }
}