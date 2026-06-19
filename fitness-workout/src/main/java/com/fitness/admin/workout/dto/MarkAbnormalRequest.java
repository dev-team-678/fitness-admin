package com.fitness.admin.workout.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MarkAbnormalRequest {

    @Size(max = 256, message = "备注长度不能超过 256")
    private String note;
}