package com.fitness.admin.ai.job;

import com.fitness.admin.ai.entity.KnowledgeBase;
import com.fitness.admin.ai.mapper.KnowledgeBaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 批量导入任务进度跟踪器(内存态)
 * - 写库阶段:batchImport 解析文件 + 批量 insert,推进 status=INDEXING
 * - 索引阶段:每 5s 由 refresh() 扫一次目标知识列表的 vector_status,更新计数
 * - 全部 indexed 或 failed 时切 DONE
 *
 * 重启后丢失,前端轮询发现 jobId 不存在会回 404(可接受,导入本身已落库)
 */
@Slf4j
@Component
public class ImportJobTracker {

    private final Map<String, ImportJobProgress> jobs = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> jobKnowledgeIds = new ConcurrentHashMap<>();

    private KnowledgeBaseMapper mapper;

    @Autowired
    public void setMapper(KnowledgeBaseMapper mapper) {
        this.mapper = mapper;
    }

    public String createJob(String fileName) {
        ImportJobProgress p = new ImportJobProgress();
        p.setJobId(UUID.randomUUID().toString().replace("-", ""));
        p.setFileName(fileName);
        p.setStatus(ImportJobProgress.Status.PARSING);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(p.getCreatedAt());
        jobs.put(p.getJobId(), p);
        jobKnowledgeIds.put(p.getJobId(), new ArrayList<>());
        return p.getJobId();
    }

    public void markIndexing(String jobId, int total, List<Long> knowledgeIds) {
        ImportJobProgress p = jobs.get(jobId);
        if (p == null) return;
        p.setStatus(ImportJobProgress.Status.INDEXING);
        p.setTotal(total);
        p.setIndexed(0);
        p.setPending(total);
        p.setFailed(0);
        p.setUpdatedAt(LocalDateTime.now());
        if (knowledgeIds != null) {
            jobKnowledgeIds.put(jobId, new ArrayList<>(knowledgeIds));
        }
    }

    public void markFailed(String jobId, String reason) {
        ImportJobProgress p = jobs.get(jobId);
        if (p == null) return;
        p.setStatus(ImportJobProgress.Status.FAILED);
        p.setErrorMessage(reason);
        p.setUpdatedAt(LocalDateTime.now());
    }

    public ImportJobProgress get(String jobId) {
        return jobs.get(jobId);
    }

    public List<Long> getKnowledgeIds(String jobId) {
        return jobKnowledgeIds.getOrDefault(jobId, Collections.emptyList());
    }

    /**
     * 每 5s 扫一次:统计 indexed / pending / failed,全部 indexed 时切 DONE
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
    public void refresh() {
        if (jobs.isEmpty() || mapper == null) return;
        for (ImportJobProgress p : jobs.values()) {
            if (p.getStatus() != ImportJobProgress.Status.INDEXING) continue;
            List<Long> ids = jobKnowledgeIds.get(p.getJobId());
            if (ids == null || ids.isEmpty()) {
                p.setStatus(ImportJobProgress.Status.DONE);
                p.setUpdatedAt(LocalDateTime.now());
                continue;
            }
            try {
                Map<String, Integer> counts = countByStatus(ids);
                int indexed = counts.getOrDefault("indexed", 0);
                int failed = counts.getOrDefault("failed", 0);
                int pending = counts.getOrDefault("pending", 0)
                        + counts.getOrDefault("indexing", 0);
                p.setIndexed(indexed);
                p.setFailed(failed);
                p.setPending(pending);
                p.setUpdatedAt(LocalDateTime.now());
                if (indexed + failed >= p.getTotal()) {
                    p.setStatus(ImportJobProgress.Status.DONE);
                }
            } catch (Exception e) {
                log.warn("ImportJobTracker 刷新失败: jobId={}, err={}", p.getJobId(), e.getMessage());
            }
        }
    }

    private Map<String, Integer> countByStatus(List<Long> ids) {
        if (mapper == null) return Collections.emptyMap();
        List<KnowledgeBase> rows = mapper.selectBatchIds(ids);
        int indexed = 0, failed = 0, pending = 0, indexing = 0;
        for (KnowledgeBase r : rows) {
            String s = r.getVectorStatus();
            if (s == null) s = "pending";
            switch (s) {
                case "indexed": indexed++; break;
                case "failed": failed++; break;
                case "indexing": indexing++; break;
                default: pending++; break;
            }
        }
        Map<String, Integer> m = new HashMap<>();
        m.put("indexed", indexed);
        m.put("failed", failed);
        m.put("pending", pending);
        m.put("indexing", indexing);
        return m;
    }
}
