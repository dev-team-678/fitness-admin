package com.fitness.admin.system.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.admin.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
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
    private final Environment environment;

    @Qualifier("nacosRestTemplate")
    private final RestTemplate restTemplate;

    @Value("${spring.cloud.nacos.config.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    @Value("${spring.cloud.nacos.config.namespace:fitness-admin}")
    private String namespace;

    @Value("${spring.cloud.nacos.config.group:DEFAULT_GROUP}")
    private String group;

    @Value("${spring.cloud.nacos.config.username:}")
    private String username;

    @Value("${spring.cloud.nacos.config.password:}")
    private String password;

    /**
     * 拼接 Nacos Open API URL。Nacos 未启用鉴权时(username/password 都为空),
     * 强制不携带凭据,避免 Nacos 把空 password 视作错误凭据返回 403/500。
     * Nacos 启用鉴权时,需在 application.yml / env 里把 nacos.username / nacos.password 配齐。
     */
    private String buildUrl() {
        StringBuilder sb = new StringBuilder("http://")
                .append(serverAddr)
                .append("/nacos/v1/cs/configs?dataId=").append(AI_DATA_ID)
                .append("&group=").append(group)
                .append("&tenant=").append(namespace);
        if (username != null && !username.isBlank()
                && password != null && !password.isBlank()) {
            sb.append("&username=").append(username);
            sb.append("&password=").append(password);
        }
        return sb.toString();
    }

    /**
     * 推送 ai 配置到 Nacos。返回 true 表示 Nacos 接受并已下发刷新事件。
     */
    public boolean publishAiConfig(Map<String, Object> aiConfigMap) {
        String yaml = toYaml(aiConfigMap);
        String url = buildUrl();
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
     * 从 Nacos 拉取 ai 配置。配置不存在时返回空 map(首次使用尚未保存),
     * Nacos 不可达时抛 BizException,前端弹错。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAiConfig() {
        String url = buildUrl();
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
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.info("Nacos 配置不存在(首次使用尚未保存),回退本地配置: dataId={}", AI_DATA_ID);
            return buildFallbackMap();
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

    /**
     * Nacos 配置不存在时,从 Spring Environment 读取当前生效的 ai.* 属性构建回退 map,
     * 让前端展示 application.yml 里的默认配置而非空白表单。
     * Binder 读出的 key 为 kebab-case(Spring Boot YAML 惯例),需转为前端期望的 camelCase。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildFallbackMap() {
        try {
            Binder binder = Binder.get(environment);
            Map<String, Object> raw = binder.bind("ai", Bindable.of(Map.class))
                    .orElseGet(LinkedHashMap::new);
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, value) -> result.put(kebabToCamel(key), convertIndexedMap(value)));
            return result;
        } catch (Exception e) {
            log.warn("读取本地 ai.* 配置失败,返回空 map: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** kebab-case → camelCase,如 api-base-url → apiBaseUrl */
    private String kebabToCamel(String key) {
        if (key == null || !key.contains("-")) return key;
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : key.toCharArray()) {
            if (c == '-') { upper = true; }
            else { sb.append(upper ? Character.toUpperCase(c) : c); upper = false; }
        }
        return sb.toString();
    }

    /** Binder 把 List 绑成 {0:x, 1:y} 的 Map,需转回 List 以匹配前端期望的数组格式 */
    @SuppressWarnings("unchecked")
    private Object convertIndexedMap(Object value) {
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            boolean allNumericKeys = !map.isEmpty() && map.keySet().stream()
                    .allMatch(k -> k.matches("\\d+"));
            if (allNumericKeys) {
                java.util.List<Object> list = new java.util.ArrayList<>();
                map.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(Integer.parseInt(a.getKey()), Integer.parseInt(b.getKey())))
                        .forEach(e -> list.add(e.getValue()));
                return list;
            }
        }
        return value;
    }
}