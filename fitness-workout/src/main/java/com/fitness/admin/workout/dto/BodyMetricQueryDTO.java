package com.fitness.admin.workout.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class BodyMetricQueryDTO {

    private Long userId;
    private LocalDate startDate;
    private LocalDate endDate;
    /** 仅看异常: 1-仅异常 0/null-全部 */
    private Integer isAbnormal;
    private Integer pageNum = 1;
    private Integer pageSize = 10;
}
