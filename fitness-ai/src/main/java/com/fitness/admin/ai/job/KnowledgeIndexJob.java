package com.fitness.admin.ai.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.ai.entity.KnowledgeBase;
import com.fitness.admin.ai.entity.VectorIndex;
import com.fitness.admin.ai.mapper.KnowledgeBaseMapper;
import com.fitness.admin.ai.mapper.VectorIndexMapper;
import com.fitness.admin.ai.rag.EmbeddingService;
import com.fitness.admin.ai.rag.KnowledgeChunker;
import com.fitness.admin.ai.rag.QdrantVectorStore;
import io.qdrant.client.grpc.JsonWithInt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库索引任务
 * 定时把 vector_status='pending' 的 knowledge_base 切块、embedding、写入 Qdrant。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeIndexJob {

    private static final int BATCH_SIZE = 20;
    private static final String SOURCE_TYPE = "knowledge";

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final VectorIndexMapper vectorIndexMapper;
    private final EmbeddingService embeddingService;
    private final KnowledgeChunker chunker;
    private final QdrantVectorStore vectorStore;
    private final AiConfig aiConfig;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void indexPending() {
        List<KnowledgeBase> pending = knowledgeBaseMapper.selectList(
                Wrappers.<KnowledgeBase>lambdaQuery()
                        .eq(KnowledgeBase::getVectorStatus, "pending")
                        .last("LIMIT " + BATCH_SIZE));
        if (pending.isEmpty()) return;

        log.info("KnowledgeIndexJob 开始处理 pending 知识: count={}", pending.size());
        for (KnowledgeBase kb : pending) {
            try {
                indexOne(kb);
            } catch (Exception e) {
                log.error("索引知识失败: id={}, title={}, err={}", kb.getId(), kb.getTitle(), e.getMessage(), e);
                markFailed(kb, e.getMessage());
            }
        }
    }

    private void indexOne(KnowledgeBase kb) {
        int chunkSize = aiConfig.getChunkSize() != null ? aiConfig.getChunkSize() : 500;
        int chunkOverlap = aiConfig.getChunkOverlap() != null ? aiConfig.getChunkOverlap() : 50;
        List<String> chunks = chunker.split(kb.getContent(), chunkSize, chunkOverlap);
        if (chunks.isEmpty()) {
            markFailed(kb, "切块结果为空");
            return;
        }
        log.debug("索引知识: id={}, chunks={}", kb.getId(), chunks.size());

        List<List<Double>> vectors = embeddingService.embedBatch(chunks);
        if (vectors.size() != chunks.size()) {
            markFailed(kb, "Embedding 数量与 chunk 数量不一致: " + vectors.size() + "/" + chunks.size());
            return;
        }

        vectorStore.deleteBySourceTypeAndId(SOURCE_TYPE, kb.getId());
        vectorIndexMapper.deleteBySource(SOURCE_TYPE, kb.getId());

        List<QdrantVectorStore.KnowledgeChunk> writes = new ArrayList<>(chunks.size());
        List<VectorIndex> records = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String vectorId = UUID.randomUUID().toString();
            Map<String, JsonWithInt.Value> payload = new HashMap<>();
            payload.put("sourceType", JsonWithInt.Value.newBuilder().setStringValue(SOURCE_TYPE).build());
            payload.put("sourceId", JsonWithInt.Value.newBuilder().setIntegerValue(kb.getId()).build());
            payload.put("chunkText", JsonWithInt.Value.newBuilder().setStringValue(truncate(chunks.get(i), 2000)).build());
            if (kb.getTitle() != null) {
                payload.put("title", JsonWithInt.Value.newBuilder().setStringValue(kb.getTitle()).build());
            }
            if (kb.getCategory() != null) {
                payload.put("category", JsonWithInt.Value.newBuilder().setStringValue(kb.getCategory()).build());
            }
            writes.add(new QdrantVectorStore.KnowledgeChunk(vectorId, vectors.get(i), payload));

            VectorIndex vi = new VectorIndex();
            vi.setSourceType(SOURCE_TYPE);
            vi.setSourceId(kb.getId());
            vi.setVectorId(vectorId);
            vi.setChunkText(truncate(chunks.get(i), 500));
            vi.setEmbeddingModel(aiConfig.getEmbeddingModel());
            vi.setCreatedAt(LocalDateTime.now());
            records.add(vi);
        }
        vectorStore.upsertChunks(writes);
        for (VectorIndex vi : records) {
            vectorIndexMapper.insert(vi);
        }

        kb.setVectorStatus("indexed");
        kb.setVectorModel(aiConfig.getEmbeddingModel());
        kb.setVectorIndexedAt(LocalDateTime.now());
        kb.setVectorError(null);
        knowledgeBaseMapper.updateById(kb);
        log.info("知识索引完成: id={}, title={}, chunks={}", kb.getId(), kb.getTitle(), chunks.size());
    }

    private void markFailed(KnowledgeBase kb, String reason) {
        try {
            kb.setVectorStatus("failed");
            String err = reason == null ? "未知错误" : reason;
            if (err.length() > 1000) err = err.substring(0, 1000);
            kb.setVectorError(err);
            kb.setVectorIndexedAt(LocalDateTime.now());
            knowledgeBaseMapper.updateById(kb);
        } catch (Exception e) {
            log.warn("写入失败状态失败: id={}, err={}", kb.getId(), e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
