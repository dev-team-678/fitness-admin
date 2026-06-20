package com.fitness.admin.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.ai.security.ApiKeyManager;
import com.fitness.admin.ai.security.EmbeddingApiKeyManager;
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

/**
 * 兼容 OpenAI Embeddings 协议的 Embedding 实现 (P2-9 升级,2026-06-20)。
 *
 * <p>主要变更:
 * <ul>
 *   <li>通过 {@link EmbeddingApiKeyManager} 取 key,而非 {@code aiConfig.getEffectiveEmbeddingApiKey()},
 *       从而支持多 key 轮转与故障转移。</li>
 *   <li>网络/认证失败时调用 {@link EmbeddingApiKeyManager#rotate()} 切到下一把 key 重试,
 *       重试上限 = key 池大小(每把 key 最多试一次)。</li>
 *   <li>错误日志脱敏(P0-2):只打印 status + error.message,不打印完整 body。</li>
 * </ul>
 *
 * <p>优先级仍然从 {@link AiConfig#getEffectiveEmbeddingApiBaseUrl()} 取 base url,
 * 因为 base url 通常只有一把(同一厂商同一 region),不需要轮转。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiCompatibleEmbeddingService implements EmbeddingService {

    private final AiConfig aiConfig;
    private final EmbeddingApiKeyManager embeddingKeyManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;

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
        String url = aiConfig.getEffectiveEmbeddingApiBaseUrl() + "/embeddings";

        // 构建请求体(只构建一次,key 切换不影响 body)
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", aiConfig.getEmbeddingModel());
        body.put("dimensions", aiConfig.getEmbeddingDimension());
        ArrayNode inputArray = body.putArray("input");
        for (String t : texts) {
            inputArray.add(t == null ? "" : t);
        }
        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new BizException("Embedding 请求体序列化失败: " + e.getMessage());
        }

        // 多 key 轮转:每把 key 最多试一次
        int attempts = 0;
        int maxAttempts = computeMaxAttempts();
        Throwable lastError = null;
        while (attempts < maxAttempts) {
            attempts++;
            String currentKey = embeddingKeyManager.current();
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + currentKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(bodyJson, MediaType.parse("application/json")))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    String safeMessage = extractSafeMessage(errorBody);
                    log.error("Embedding API 调用失败: status={}, message={}, attempt={}/{}",
                            response.code(), safeMessage, attempts, maxAttempts);

                    // 401/403/429 → key 无效或限流,触发轮转
                    if (isRetryableStatus(response.code()) && attempts < maxAttempts) {
                        embeddingKeyManager.rotate();
                        continue;
                    }
                    throw new BizException("Embedding 调用失败: HTTP " + response.code());
                }

                // 成功
                embeddingKeyManager.markSuccess();
                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode data = root.get("data");
                if (data == null || !data.isArray()) {
                    log.error("Embedding 响应格式异常: {}", truncateForLog(responseBody));
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
            } catch (IOException e) {
                // 网络异常不轮转 key(网络问题换 key 也没用),直接抛出
                log.error("Embedding 网络异常", e);
                throw new BizException("Embedding 网络异常: " + e.getMessage());
            } catch (ApiKeyManager.AllKeysExhaustedException e) {
                lastError = e;
                log.error("Embedding 所有 key 都已耗尽: {}", e.getMessage());
                break;
            }
        }
        throw new BizException("Embedding 调用失败,所有 key 均不可用: "
                + (lastError == null ? "no attempts" : lastError.getMessage()));
    }

    /**
     * 重试上限 = key 池大小(每把 key 最多试一次)。
     * 若只有 1 把,只试 1 次,避免死循环。
     */
    private int computeMaxAttempts() {
        List<com.fitness.admin.ai.security.ApiKeyEntry> keys =
                aiConfig.getEmbeddingApiKeys();
        int poolSize = keys == null ? 0 : keys.size();
        if (poolSize == 0 && aiConfig.getEmbeddingApiKey() != null
                && !aiConfig.getEmbeddingApiKey().isBlank()) {
            poolSize = 1; // 兼容单 key 模式
        }
        return Math.max(poolSize, 1);
    }

    private static boolean isRetryableStatus(int code) {
        return code == 401 || code == 403 || code == 429;
    }

    /**
     * 从阿里云/OpenAI 返回的 body 中安全提取 error.message,丢弃其他字段(防日志泄露)。
     */
    private String extractSafeMessage(String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode err = root.get("error");
            if (err != null && err.hasNonNull("message")) {
                return err.get("message").asText();
            }
            JsonNode message = root.get("message");
            if (message != null && message.isTextual()) {
                return message.asText();
            }
        } catch (Exception ignored) { }
        return truncateForLog(body);
    }

    private String truncateForLog(String body) {
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}