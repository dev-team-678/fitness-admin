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
 * Claude(Anthropic)协议客户端 (P2-9 升级,2026-06-20)。
 *
 * <p>主要变更:与 {@link OpenAiCompatibleLlmClient} 一致,通过 {@link ApiKeyManager} 取 key,
 * 401/403/429 时切到下一把 key 重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeLlmClient implements LlmClient {

    private final AiConfig aiConfig;
    private final ApiKeyManager apiKeyManager;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String provider() {
        return "claude";
    }

    @Override
    public AiService.LlmResponse chat(List<AiService.ChatMessage> messages) {
        String url = aiConfig.getApiBaseUrl();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", aiConfig.getModel());
        body.put("max_tokens", aiConfig.getMaxTokens());

        String systemMessage = aiConfig.getSystemPrompt();
        ArrayNode arr = body.putArray("messages");
        for (AiService.ChatMessage m : messages) {
            if ("system".equals(m.getRole())) {
                systemMessage = m.getContent();
            } else {
                ObjectNode n = arr.addObject();
                n.put("role", m.getRole());
                n.put("content", m.getContent());
            }
        }
        if (systemMessage != null) body.put("system", systemMessage);
        String bodyJson = body.toString();

        int attempts = 0;
        int maxAttempts = computeMaxAttempts();
        while (attempts < maxAttempts) {
            attempts++;
            String currentKey = apiKeyManager.current();
            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("x-api-key", currentKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(bodyJson, MediaType.parse("application/json")))
                    .build();
            try (Response response = httpClient.newCall(req).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "unknown";
                    String safe = extractSafeMessage(err);
                    log.error("Claude 调用失败: status={}, message={}, attempt={}/{}",
                            response.code(), safe, attempts, maxAttempts);
                    if (isRetryableStatus(response.code()) && attempts < maxAttempts) {
                        apiKeyManager.rotate();
                        continue;
                    }
                    throw new RuntimeException("HTTP " + response.code() + ": " + safe);
                }

                apiKeyManager.markSuccess();
                String resp = response.body().string();
                JsonNode root = objectMapper.readTree(resp);
                String content = root.get("content").get(0).get("text").asText();
                int p = 0, c = 0;
                JsonNode usage = root.get("usage");
                if (usage != null) {
                    p = usage.path("input_tokens").asInt(0);
                    c = usage.path("output_tokens").asInt(0);
                }
                return new AiService.LlmResponse(content, p, c, p + c, 0L);
            } catch (ApiKeyManager.AllKeysExhaustedException e) {
                throw new RuntimeException("Claude 调用失败,所有 key 均不可用: " + e.getMessage(), e);
            } catch (IOException e) {
                throw new RuntimeException("Claude 调用失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("Claude 调用失败,已达最大重试次数");
    }

    private int computeMaxAttempts() {
        int poolSize = aiConfig.getApiKeys() == null ? 0 : aiConfig.getApiKeys().size();
        if (poolSize == 0) poolSize = 1;
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
        } catch (Exception ignored) { }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
