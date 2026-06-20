package com.fitness.admin.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.ai.security.ApiKeyManager;
import com.fitness.admin.ai.service.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * OpenAI 兼容协议客户端 (P2-9 升级,2026-06-20)。
 *
 * <p>主要变更:
 * <ul>
 *   <li>通过 {@link ApiKeyManager} 取 key,而非 {@code aiConfig.getApiKey()},支持多 key 轮转。</li>
 *   <li>401/403/429 时调用 {@link ApiKeyManager#rotate()} 切到下一把 key 重试。</li>
 *   <li>错误日志只打 status + 安全 message,不打印完整 body。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final AiConfig aiConfig;
    private final ApiKeyManager apiKeyManager;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String provider() {
        return normalize(aiConfig.getProvider());
    }

    @Override
    public AiService.LlmResponse chat(List<AiService.ChatMessage> messages) {
        String url = aiConfig.getApiBaseUrl() + "/chat/completions";

        // 构建请求体(只构建一次,key 切换不影响)
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", aiConfig.getModel());
        requestBody.put("max_tokens", aiConfig.getMaxTokens());
        requestBody.put("temperature", aiConfig.getTemperature());
        ArrayNode arr = requestBody.putArray("messages");
        for (AiService.ChatMessage m : messages) {
            ObjectNode n = arr.addObject();
            n.put("role", m.getRole());
            n.put("content", m.getContent());
        }
        String bodyJson;
        try {
            bodyJson = requestBody.toString();
        } catch (Exception e) {
            throw new RuntimeException("请求体序列化失败: " + e.getMessage(), e);
        }

        int attempts = 0;
        int maxAttempts = computeMaxAttempts();
        while (attempts < maxAttempts) {
            attempts++;
            String currentKey = apiKeyManager.current();
            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + currentKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(bodyJson, MediaType.parse("application/json")))
                    .build();
            try (Response response = httpClient.newCall(req).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "unknown";
                    String safe = extractSafeMessage(err);
                    log.error("LLM 调用失败: status={}, message={}, attempt={}/{}",
                            response.code(), safe, attempts, maxAttempts);

                    if (isRetryableStatus(response.code()) && attempts < maxAttempts) {
                        apiKeyManager.rotate();
                        continue;
                    }
                    throw new RuntimeException("HTTP " + response.code() + ": " + safe);
                }

                apiKeyManager.markSuccess();
                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);
                String content = root.get("choices").get(0).get("message").get("content").asText();
                int p = 0, c = 0, t = 0;
                JsonNode usage = root.get("usage");
                if (usage != null) {
                    p = usage.path("prompt_tokens").asInt(0);
                    c = usage.path("completion_tokens").asInt(0);
                    t = usage.path("total_tokens").asInt(0);
                }
                return new AiService.LlmResponse(content, p, c, t, 0L);
            } catch (ApiKeyManager.AllKeysExhaustedException e) {
                throw new RuntimeException("LLM 调用失败,所有 key 均不可用: " + e.getMessage(), e);
            } catch (IOException e) {
                throw new RuntimeException("OpenAI 兼容协议调用失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("LLM 调用失败,已达最大重试次数");
    }

    private int computeMaxAttempts() {
        int poolSize = aiConfig.getApiKeys() == null ? 0 : aiConfig.getApiKeys().size();
        if (poolSize == 0) poolSize = 1; // 单 key 兼容
        return Math.max(poolSize, 1);
    }

    private static boolean isRetryableStatus(int code) {
        return code == 401 || code == 403 || code == 429;
    }

    private String extractSafeMessage(String body) {
        if (body == null || body.isEmpty()) return "unknown";
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
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    private static String normalize(String p) {
        if (p == null || p.isBlank()) return "openai";
        return p.toLowerCase();
    }
}
