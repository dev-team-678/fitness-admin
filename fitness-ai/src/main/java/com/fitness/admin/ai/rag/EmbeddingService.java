package com.fitness.admin.ai.rag;

import java.util.List;

/**
 * Embedding 服务接口
 * 将文本转为稠密向量,供向量检索使用
 */
public interface EmbeddingService {

    /**
     * 单条文本向量化
     */
    List<Double> embed(String text);

    /**
     * 批量向量化(同一调用,减少网络往返)
     */
    List<List<Double>> embedBatch(List<String> texts);
}
