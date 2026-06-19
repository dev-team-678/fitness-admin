package com.fitness.admin.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.ai.service.AiService;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * OpenAI 兼容协议客户端(OpenAI / DeepSeek 共用,协议完全相同)。
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final AiConfig aiConfig;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String provider() {
        // provider 名由 AiConfig 决定:DeepSeek 时也是走 OpenAI 协议,但 Bean 名要区分,
        // 这里返回当前 AiConfig 的 provider 字符串。
        return normalize(aiConfig.getProvider());
    }

    @Override
    public AiService.LlmResponse chat(List<AiService.ChatMessage> messages) {
        try {
            String url = aiConfig.getApiBaseUrl() + "/chat/completions";
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
            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + aiConfig.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody.toString(),
                            MediaType.parse("application/json")))
                    .build();
            try (Response response = httpClient.newCall(req).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "unknown";
                    throw new RuntimeException("HTTP " + response.code() + ": " + err);
                }
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
            }
        } catch (IOException e) {
            throw new RuntimeException("OpenAI 兼容协议调用失败: " + e.getMessage(), e);
        }
    }

    private static String normalize(String p) {
        if (p == null || p.isBlank()) return "openai";
        // DeepSeek 也是 OpenAI 协议,这里统一映射到 "openai" Bean 名
        // (真正的 provider 名 aiConfig 仍为 "deepseek",由 AiConfig.getProvider 决定)
        return p.toLowerCase();
    }
}
