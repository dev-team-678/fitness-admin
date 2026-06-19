package com.fitness.admin.ai.service;

import com.fitness.admin.ai.config.AiConfig;
import com.fitness.admin.ai.service.AiService.ChatMessage;
import com.fitness.admin.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 连接测试服务:
 * - 用请求体里的 ai.* 临时覆盖(不写 Nacos)真实调一次 LLM。
 * - 返回 latencyMs / reply / status / error 字段供前端展示。
 *
 * <p>不阻塞 Nacos 推送链路,允许在保存前先 dry-run 验证 key 是否有效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConnectionTestService {

    private final AiService aiService;
    private final AiConfig aiConfig;

    public Map<String, Object> test(Map<String, Object> effectiveConfig) {
        Map<String, Object> result = new LinkedHashMap<>();
        Snapshot snapshot = Snapshot.capture(aiConfig);

        try {
            Snapshot.applyTo(aiConfig, effectiveConfig);

            long t0 = System.currentTimeMillis();
            String reply;
            try {
                reply = aiService.chat(List.of(
                        new ChatMessage("system", "You are a connectivity test. Reply with 'pong' only."),
                        new ChatMessage("user", "ping")));
            } finally {
                Snapshot.restore(aiConfig, snapshot);
            }
            long latencyMs = System.currentTimeMillis() - t0;

            result.put("status", "ok");
            result.put("latencyMs", latencyMs);
            result.put("reply", reply);
            result.put("provider", aiConfig.getProvider());
            result.put("model", aiConfig.getModel());
            result.put("apiBaseUrl", aiConfig.getApiBaseUrl());
            return result;
        } catch (BizException e) {
            Snapshot.restore(aiConfig, snapshot);
            result.put("status", "fail");
            result.put("error", e.getMessage());
            return result;
        } catch (Exception e) {
            Snapshot.restore(aiConfig, snapshot);
            log.error("AI 连接测试失败", e);
            result.put("status", "fail");
            result.put("error", e.getMessage());
            return result;
        }
    }

    private static class Snapshot {
        String provider, apiBaseUrl, model, apiKey;
        Integer maxTokens;
        Double temperature;

        static Snapshot capture(AiConfig c) {
            Snapshot s = new Snapshot();
            s.provider = c.getProvider();
            s.apiBaseUrl = c.getApiBaseUrl();
            s.model = c.getModel();
            s.apiKey = c.getApiKey();
            s.maxTokens = c.getMaxTokens();
            s.temperature = c.getTemperature();
            return s;
        }

        static void applyTo(AiConfig c, Map<String, Object> m) {
            if (m == null) return;
            setIfPresent(m, "provider", c::setProvider);
            setIfPresent(m, "apiBaseUrl", c::setApiBaseUrl);
            setIfPresent(m, "model", c::setModel);
            setIfPresent(m, "apiKey", c::setApiKey);
            Object max = m.get("maxTokens");
            if (max instanceof Number n) c.setMaxTokens(n.intValue());
            Object tmp = m.get("temperature");
            if (tmp instanceof Number n) c.setTemperature(n.doubleValue());
        }

        static void restore(AiConfig c, Snapshot s) {
            if (s == null) return;
            c.setProvider(s.provider);
            c.setApiBaseUrl(s.apiBaseUrl);
            c.setModel(s.model);
            c.setApiKey(s.apiKey);
            c.setMaxTokens(s.maxTokens);
            c.setTemperature(s.temperature);
        }

        static void setIfPresent(Map<String, Object> m, String k, java.util.function.Consumer<String> setter) {
            Object v = m.get(k);
            if (v instanceof String s && !s.isEmpty()) setter.accept(s);
        }
    }
}
