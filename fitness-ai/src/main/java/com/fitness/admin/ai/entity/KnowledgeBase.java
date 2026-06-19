package com.fitness.admin.ai.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fitness.admin.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "knowledge_base", autoResultMap = true)
public class KnowledgeBase extends BaseEntity {

    private String title;
    private String content;
    private String category;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    private String source;
    private String vectorStatus;
    private String vectorId;
    private String vectorModel;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime vectorIndexedAt;

    private String vectorError;
    private Integer status;
}
