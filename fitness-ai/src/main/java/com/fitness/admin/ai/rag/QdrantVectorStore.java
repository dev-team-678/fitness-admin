package com.fitness.admin.ai.rag;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Qdrant 向量存储封装
 * 负责 collection 管理、向量写入、相似度检索、按 source 删除。
 */
@Slf4j
public class QdrantVectorStore {

    private final String host;
    private final int port;
    private final String apiKey;
    private final String collection;
    private final int dimension;

    private QdrantClient client;

    public QdrantVectorStore(String host, int port, String apiKey, String collection, int dimension) {
        this.host = host;
        this.port = port;
        this.apiKey = apiKey;
        this.collection = collection;
        this.dimension = dimension;
    }

    @PostConstruct
    public void init() {
        try {
            QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, port, false)
                    .withTimeout(Duration.ofSeconds(30));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.withApiKey(apiKey);
            }
            this.client = new QdrantClient(builder.build());
            ensureCollection();
            log.info("QdrantVectorStore 初始化完成: host={}:{}, collection={}", host, port, collection);
        } catch (Exception e) {
            log.error("Qdrant 初始化失败,RAG 检索将不可用: {}", e.getMessage(), e);
            this.client = null;
        }
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 若 collection 不存在则创建(Cosine 距离 + 指定维度)
     */
    public void ensureCollection() {
        if (client == null) return;
        try {
            Boolean exists = client.collectionExistsAsync(collection).get(10, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(exists)) {
                return;
            }
            Collections.VectorParams vectorParams = Collections.VectorParams.newBuilder()
                    .setSize(dimension)
                    .setDistance(Collections.Distance.Cosine)
                    .build();
            client.createCollectionAsync(collection, vectorParams).get(30, TimeUnit.SECONDS);
            log.info("Qdrant collection ensured: {}", collection);
        } catch (Exception e) {
            log.error("ensureCollection 失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 写入一批知识块到向量库
     */
    public void upsertChunks(List<KnowledgeChunk> chunks) {
        if (client == null || chunks == null || chunks.isEmpty()) return;
        try {
            List<Points.PointStruct> points = new ArrayList<>(chunks.size());
            for (KnowledgeChunk chunk : chunks) {
                Points.PointStruct.Builder psBuilder = Points.PointStruct.newBuilder();
                psBuilder.setId(Points.PointId.newBuilder().setUuid(chunk.getVectorId()));
                psBuilder.setVectors(Points.Vectors.newBuilder()
                        .setVector(Points.Vector.newBuilder()
                                .addAllData(floatListToFloats(chunk.getVector()))
                                .build())
                        .build());
                Map<String, JsonWithInt.Value> payload = chunk.getPayload();
                if (payload != null) {
                    payload.forEach(psBuilder::putPayload);
                }
                points.add(psBuilder.build());
            }
            client.upsertAsync(collection, points).get(60, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Qdrant upsert interrupted", ie);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException("Qdrant upsert 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检索 Top-K
     */
    public List<ScoredChunk> search(List<Float> queryVec, int topK, double minScore) {
        if (client == null || queryVec == null || queryVec.isEmpty()) return List.of();
        try {
            Points.SearchPoints.Builder searchBuilder = Points.SearchPoints.newBuilder()
                    .setCollectionName(collection)
                    .addAllVector(queryVec)
                    .setLimit(topK)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setScoreThreshold((float) minScore);
            List<Points.ScoredPoint> hits = client.searchAsync(searchBuilder.build()).get(30, TimeUnit.SECONDS);
            List<ScoredChunk> result = new ArrayList<>(hits.size());
            for (Points.ScoredPoint hit : hits) {
                result.add(new ScoredChunk(
                        hit.getId().getUuid(),
                        hit.getScore(),
                        new java.util.HashMap<>(hit.getPayloadMap())));
            }
            return result;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Qdrant search 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 按 (sourceType, sourceId) 删除向量
     */
    public void deleteBySourceTypeAndId(String sourceType, Long sourceId) {
        if (client == null) return;
        try {
            Points.Condition condition = Points.Condition.newBuilder()
                    .setField(Points.FieldCondition.newBuilder()
                            .setKey("sourceType")
                            .setMatch(Points.Match.newBuilder().setKeyword(sourceType).build())
                            .build())
                    .build();
            Points.Filter filter = Points.Filter.newBuilder()
                    .addMust(condition)
                    .addMust(Points.Condition.newBuilder()
                            .setField(Points.FieldCondition.newBuilder()
                                    .setKey("sourceId")
                                    .setMatch(Points.Match.newBuilder().setInteger(sourceId).build())
                                    .build())
                            .build())
                    .build();
            client.deleteAsync(collection, filter).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Qdrant deleteBySourceTypeAndId 失败: source={}:{}, err={}", sourceType, sourceId, e.getMessage());
        }
    }

    private static List<Float> floatListToFloats(List<Double> doubles) {
        List<Float> result = new ArrayList<>(doubles.size());
        for (Double d : doubles) {
            result.add(d == null ? 0f : d.floatValue());
        }
        return result;
    }

    /**
     * 知识块(待写入)
     */
    public static class KnowledgeChunk {
        private final String vectorId;
        private final List<Double> vector;
        private final Map<String, JsonWithInt.Value> payload;

        public KnowledgeChunk(String vectorId, List<Double> vector, Map<String, JsonWithInt.Value> payload) {
            this.vectorId = vectorId;
            this.vector = vector;
            this.payload = payload;
        }

        public String getVectorId() { return vectorId; }
        public List<Double> getVector() { return vector; }
        public Map<String, JsonWithInt.Value> getPayload() { return payload; }
    }

    /**
     * 检索结果
     */
    public static class ScoredChunk {
        private final String vectorId;
        private final double score;
        private final Map<String, JsonWithInt.Value> payload;

        public ScoredChunk(String vectorId, double score, Map<String, JsonWithInt.Value> payload) {
            this.vectorId = vectorId;
            this.score = score;
            this.payload = payload;
        }

        public String getVectorId() { return vectorId; }
        public double getScore() { return score; }
        public Map<String, JsonWithInt.Value> getPayload() { return payload; }
    }
}
