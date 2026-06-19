package com.fitness.admin.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 兼容 OpenAI Embeddings 协议的 Embedding 实现。
 * 优先使用 AiConfig 中 embedding 专用的 apiBaseUrl/apiKey,
 * 未配置时回退到聊天 API 的 apiBaseUrl/apiKey。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiCompatibleEmbeddingService implements EmbeddingService {

    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    @Override
    public List<Double> embed(String text) {
        List<List<Double>> batch = embedBatch(List.of(text));
        return batch.isEmpty() ? List.of() : batch.get(0);
    }

    @Override
    public List<List<Double>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            String url = aiConfig.getEffectiveEmbeddingApiBaseUrl() + "/embeddings";

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", aiConfig.getEmbeddingModel());
            body.put("dimensions", aiConfig.getEmbeddingDimension());
            ArrayNode inputArray = body.putArray("input");
            for (String t : texts) {
                inputArray.add(t == null ? "" : t);
            }

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + aiConfig.getEffectiveEmbeddingApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(objectMapper.writeValueAsString(body),
                            MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    log.error("Embedding API 调用失败: status={}, body={}", response.code(), errorBody);
                    throw new BizException("Embedding 调用失败: HTTP " + response.code());
                }

                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode data = root.get("data");
                if (data == null || !data.isArray()) {
                    log.error("Embedding 响应格式异常: {}", responseBody);
                    throw new BizException("Embedding 响应格式异常");
                }

                // OpenAI 返回按 index 排序,先按 index 排序再读取
                List<int[]> indexed = new ArrayList<>();
                for (int i = 0; i < data.size(); i++) {
                    JsonNode item = data.get(i);
                    int idx = item.has("index") ? item.get("index").asInt() : i;
                    indexed.add(new int[]{idx, i});
                }
                indexed.sort((a, b) -> Integer.compare(a[0], b[0]));

                List<List<Double>> result = new ArrayList<>(data.size());
                for (int[] pair : indexed) {
                    JsonNode item = data.get(pair[1]);
                    JsonNode embedding = item.get("embedding");
                    List<Double> vec = new ArrayList<>(embedding.size());
                    for (int j = 0; j < embedding.size(); j++) {
                        vec.add(embedding.get(j).asDouble());
                    }
                    result.add(vec);
                }
                return result;
            }
        } catch (IOException e) {
            log.error("Embedding 网络异常", e);
            throw new BizException("Embedding 网络异常: " + e.getMessage());
        }
    }
}
