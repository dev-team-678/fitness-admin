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
 * Claude(Anthropic)协议客户端。
 */
@Component
@RequiredArgsConstructor
public class ClaudeLlmClient implements LlmClient {

    private final AiConfig aiConfig;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String provider() {
        return "claude";
    }

    @Override
    public AiService.LlmResponse chat(List<AiService.ChatMessage> messages) {
        try {
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

            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("x-api-key", aiConfig.getApiKey())
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json")))
                    .build();
            try (Response response = httpClient.newCall(req).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "unknown";
                    throw new RuntimeException("HTTP " + response.code() + ": " + err);
                }
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
            }
        } catch (IOException e) {
            throw new RuntimeException("Claude 调用失败: " + e.getMessage(), e);
        }
    }
}
