package com.fitness.admin.system.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.admin.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nacos Open API 客户端:
 *  - publishAiConfig: 接收 ai-config.vue 上送的 map,序列化为 yaml,推送到 Nacos。
 *  - getAiConfig:     拉取 Nacos 当前 ai.* 配置,反序列化为 map 返回。
 *
 * 推送成功后 Nacos 自动推 @RefreshScope 通知,业务侧 AiConfig 内存立即刷新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NacosConfigPublisher {

    public static final String AI_DATA_ID = "fitness-admin-ai.yaml";

    private final ObjectMapper objectMapper;

    @Qualifier("nacosRestTemplate")
    private final RestTemplate restTemplate;

    @Value("${spring.cloud.nacos.config.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    @Value("${spring.cloud.nacos.config.namespace:fitness-admin}")
    private String namespace;

    @Value("${spring.cloud.nacos.config.group:DEFAULT_GROUP}")
    private String group;

    @Value("${spring.cloud.nacos.config.username:nacos}")
    private String username;

    @Value("${spring.cloud.nacos.config.password:nacos}")
    private String password;

    /**
     * 推送 ai 配置到 Nacos。返回 true 表示 Nacos 接受并已下发刷新事件。
     */
    public boolean publishAiConfig(Map<String, Object> aiConfigMap) {
        String yaml = toYaml(aiConfigMap);
        String url = String.format(
                "http://%s/v1/cs/configs?dataId=%s&group=%s&tenant=%s&username=%s&password=%s",
                serverAddr, AI_DATA_ID, group, namespace, username, password);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("content", yaml);
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    url, new HttpEntity<>(form, headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || !"true".equalsIgnoreCase(resp.getBody())) {
                log.error("Nacos 推送失败 status={} body={}", resp.getStatusCode(), resp.getBody());
                throw new BizException("Nacos 推送失败: " + resp.getBody());
            }
            log.info("已推送 ai 配置到 Nacos: dataId={} bytes={}", AI_DATA_ID, yaml.length());
            return true;
        } catch (RestClientException e) {
            log.error("调用 Nacos Open API 失败", e);
            throw new BizException("Nacos 不可达: " + e.getMessage());
        }
    }

    /**
     * 从 Nacos 拉取 ai 配置。Nacos 不可达时抛 BizException,前端弹错。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAiConfig() {
        String url = String.format(
                "http://%s/v1/cs/configs?dataId=%s&group=%s&tenant=%s&username=%s&password=%s",
                serverAddr, AI_DATA_ID, group, namespace, username, password);
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new BizException("Nacos 拉取失败: HTTP " + resp.getStatusCode());
            }
            String body = resp.getBody();
            if (body == null || body.isBlank()) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> root = new Yaml().load(body);
            Object ai = root == null ? null : root.get("ai");
            if (ai instanceof Map) {
                return (Map<String, Object>) ai;
            }
            return new LinkedHashMap<>();
        } catch (RestClientException e) {
            log.error("调用 Nacos Open API 失败", e);
            throw new BizException("Nacos 不可达: " + e.getMessage());
        }
    }

    private String toYaml(Map<String, Object> aiConfigMap) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setPrettyFlow(true);
        Yaml yaml = new Yaml(opts);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        // 过滤 null,避免 Nacos 端出现 ai.x: null
        Map<String, Object> filtered = new LinkedHashMap<>();
        if (aiConfigMap != null) {
            aiConfigMap.forEach((k, v) -> {
                if (v != null) filtered.put(k, v);
            });
        }
        wrapper.put("ai", filtered);
        return yaml.dump(wrapper);
    }
}