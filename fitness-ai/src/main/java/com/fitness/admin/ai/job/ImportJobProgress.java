package com.fitness.admin.ai.job;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批量导入任务的内存态进度(给前端轮询用)
 * 导入本身只解析文件 + 写库,真正的向量化由 KnowledgeIndexJob 异步消费。
 * 这里跟踪:目标 N 条 / 已 indexed / pending / failed / 完成时间
 */
@Data
public class ImportJobProgress {

    public enum Status {
        /** 正在解析 + 写库 */
        PARSING,
        /** 写库完成,等 KnowledgeIndexJob 消费 */
        INDEXING,
        /** 全部条目已 indexed(成功)或 done(允许部分失败) */
        DONE,
        /** 解析失败、文件为空等 */
        FAILED
    }

    private String jobId;
    private String fileName;
    private Status status;
    private int total;
    private int indexed;
    private int pending;
    private int failed;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
