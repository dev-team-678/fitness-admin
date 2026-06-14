package com.fitness.admin.community.dto;

import lombok.Data;

import java.util.List;

/**
 * 提交举报请求
 */
@Data
public class CreateReportRequest {
    /** 举报目标ID (帖子ID 或 评论ID) */
    private Long targetId;
    /** 举报目标类型: post / comment */
    private String targetType;
    /** 举报原因 (预设类别) */
    private String reason;
    /** 详细描述 (可选) */
    private String description;
    /** 截图URL列表 (可选) */
    private List<String> images;
}
