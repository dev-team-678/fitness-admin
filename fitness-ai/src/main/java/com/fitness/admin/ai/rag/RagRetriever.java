package com.fitness.admin.ai.rag;

import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.ai.entity.AiChatMessage;
import io.qdrant.client.grpc.JsonWithInt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 检索器
 * 1) 调 Embedding 接口把 query 转成向量
 * 2) 在 Qdrant 中按 sourceType=knowledge 检索 Top-K
 * 3) 把结果转成 AiChatMessage.RagReference 列表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetriever {

    private final EmbeddingService embeddingService;
    private final QdrantVectorStore vectorStore;
    private final AiConfig aiConfig;

    public List<AiChatMessage.RagReference> retrieve(String query, int topK, double minScore) {
        if (query == null || query.isBlank()) return List.of();
        try {
            List<Double> vec = embeddingService.embed(query);
            if (vec == null || vec.isEmpty()) return List.of();
            List<Float> queryVec = new ArrayList<>(vec.size());
            for (Double d : vec) queryVec.add(d == null ? 0f : d.floatValue());
            List<QdrantVectorStore.ScoredChunk> hits = vectorStore.search(queryVec, topK, minScore);
            List<AiChatMessage.RagReference> result = new ArrayList<>(hits.size());
            for (QdrantVectorStore.ScoredChunk hit : hits) {
                AiChatMessage.RagReference ref = new AiChatMessage.RagReference();
                ref.setId(extractLong(hit.getPayload(), "sourceId"));
                ref.setSource(stringValue(hit.getPayload(), "sourceType"));
                ref.setScore(hit.getScore());
                ref.setTitle(stringValue(hit.getPayload(), "title"));
                ref.setCategoryName(stringValue(hit.getPayload(), "category"));
                result.add(ref);
            }
            return result;
        } catch (Exception e) {
            log.warn("RAG 检索失败,降级为不引用知识库: {}", e.getMessage());
            return List.of();
        }
    }

    public List<AiChatMessage.RagReference> retrieve(String query) {
        Integer topK = aiConfig.getRagTopK() != null ? aiConfig.getRagTopK() : 5;
        Double minScore = aiConfig.getRagMinScore() != null ? aiConfig.getRagMinScore() : 0.6;
        return retrieve(query, topK, minScore);
    }

    private static String stringValue(java.util.Map<String, JsonWithInt.Value> payload, String key) {
        JsonWithInt.Value v = payload == null ? null : payload.get(key);
        if (v == null) return null;
        try {
            return v.getStringValue();
        } catch (Exception e) {
            return null;
        }
    }

    private static Long extractLong(java.util.Map<String, JsonWithInt.Value> payload, String key) {
        JsonWithInt.Value v = payload == null ? null : payload.get(key);
        if (v == null) return null;
        try {
            return v.getIntegerValue();
        } catch (Exception e) {
            try {
                return Long.parseLong(v.getStringValue());
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
