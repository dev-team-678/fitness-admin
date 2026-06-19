package com.fitness.admin.ai.config;

import com.fitness.admin.ai.rag.KnowledgeChunker;
import com.fitness.admin.ai.rag.QdrantVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 相关 bean 注册(QdrantVectorStore 需 @PostConstruct 初始化)
 */
@Configuration
public class RagConfig {

    @Bean
    public QdrantVectorStore qdrantVectorStore(AiConfig aiConfig) {
        String url = aiConfig.getQdrantUrl() == null ? "localhost:6334" : aiConfig.getQdrantUrl();
        String host = url;
        int port = 6334;
        int colon = url.lastIndexOf(':');
        if (colon > 0) {
            host = url.substring(0, colon);
            try {
                port = Integer.parseInt(url.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                port = 6334;
            }
        }
        int dim = aiConfig.getEmbeddingDimension() == null ? 1536 : aiConfig.getEmbeddingDimension();
        return new QdrantVectorStore(
                host,
                port,
                aiConfig.getQdrantApiKey(),
                aiConfig.getQdrantCollection(),
                dim);
    }

    @Bean
    public KnowledgeChunker knowledgeChunker() {
        return new KnowledgeChunker();
    }
}
