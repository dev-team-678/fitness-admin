package com.fitness.admin.workout.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.format.DateTimeFormat;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class WorkoutLogExportVO {

    @ExcelProperty("记录ID")
    @ColumnWidth(10)
    private Long id;

    @ExcelProperty("用户ID")
    @ColumnWidth(10)
    private Long userId;

    @ExcelProperty("训练日期")
    @DateTimeFormat("yyyy-MM-dd")
    @ColumnWidth(14)
    private LocalDate workoutDate;

    @ExcelProperty("开始时间")
    @DateTimeFormat("yyyy-MM-dd HH:mm:ss")
    @ColumnWidth(22)
    private LocalDateTime startTime;

    @ExcelProperty("结束时间")
    @DateTimeFormat("yyyy-MM-dd HH:mm:ss")
    @ColumnWidth(22)
    private LocalDateTime endTime;

    @ExcelProperty("时长(分钟)")
    @ColumnWidth(12)
    private Integer durationMin;

    @ExcelProperty("总容量(kg)")
    @ColumnWidth(12)
    private BigDecimal totalVolumeKg;

    @ExcelProperty("总组数")
    @ColumnWidth(10)
    private Integer totalSets;

    @ExcelProperty("预估消耗(千卡)")
    @ColumnWidth(16)
    private BigDecimal estimatedCalories;

    @ExcelProperty("状态")
    @ColumnWidth(10)
    private String status;

    @ExcelProperty("备注")
    @ColumnWidth(30)
    private String notes;

    @ExcelProperty("评分")
    @ColumnWidth(8)
    private Integer feelingScore;

    @ExcelProperty("RPE")
    @ColumnWidth(8)
    private Integer rpe;
}
