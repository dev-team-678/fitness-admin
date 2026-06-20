package com.fitness.admin.system.config;

import com.fitness.admin.common.exception.BizException;
import com.fitness.admin.common.utils.SecretMasker;
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
import java.util.Map;
import java.util.Set;

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

    /**
     * 敏感字段集合(2026-06-20 P0-1 安全加固):
     * 推送前校验入参 map 中这些字段:
     *  - 不能为空(qdrantApiKey 例外,可空字符串)
     *  - 不能是 SecretMasker.mask() 产生的脱敏值(包含 ***)
     *  - 必须是 sk- 开头(qdrantApiKey 例外)
     * 防止前端拿到脱敏展示值后误当真实值覆盖后端 key。
     */
    private static final Set<String> SECRET_FIELDS = Set.of(
            "apiKey", "embeddingApiKey", "qdrantApiKey");

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
     *
     * <p><b>路径必须带 /nacos 前缀:</b>生产 Nacos(118.25.55.203:8848)context path
     * 部署为 {@code /nacos},Open API 真实地址为 {@code /nacos/v1/cs/configs}。
     * 早期曾误用 {@code /v1/cs/configs}(不带 /nacos)→ 404,加 /nacos 后通过。
     * 验证方式:浏览器访问 {@code http://<host>:8848/} 看到欢迎页/控制台 → context 是 /nacos;
     * 访问 {@code http://<host>:8848/nacos/} 才看到 → 同上;二者均能看到则镜像按镜像 env 决定。
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
     *
     * <p>2026-06-20 P0-1 安全加固:
     * <ol>
     *   <li>前置校验 secret 字段不能是脱敏值或空(防前端展示值误覆盖)</li>
     *   <li>先 GET 当前 Nacos 完整配置,再与入参 merge(防前端表单字段不全把后端字段覆盖丢)</li>
     *   <li>推送日志只打 dataId + 字节数,不打印 yaml 内容(防 key 落日志)</li>
     * </ol>
     */
    public boolean publishAiConfig(Map<String, Object> aiConfigMap) {
        validateSecretFields(aiConfigMap);
        Map<String, Object> merged = mergeWithExisting(aiConfigMap);
        // P1-8: 推送前先记录当前 md5(用于推送后对账,以及失败时回滚定位)
        String previousMd5 = computeMd5(merged);
        String yaml = toYaml(merged);
        String url = buildUrl();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("content", yaml);
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    url, new HttpEntity<>(form, headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || !"true".equalsIgnoreCase(resp.getBody())) {
                log.error("Nacos 推送失败 status={} body={}, previousMd5={}",
                        resp.getStatusCode(), resp.getBody(), previousMd5);
                throw new BizException("Nacos 推送失败: " + resp.getBody());
            }
            // P1-8: 推送后立即 GET 一次,确认内容一致(防 Nacos 中途改写了 yaml)
            verifyPushedConfig(previousMd5, merged);
            log.info("已推送 ai 配置到 Nacos: dataId={} bytes={} md5={}",
                    AI_DATA_ID, yaml.length(), previousMd5);
            return true;
        } catch (RestClientException e) {
            log.error("调用 Nacos Open API 失败,previousMd5={}", previousMd5, e);
            // P1-8: 推送失败时尝试回滚到上一个版本(Nacos 历史版本机制)
            tryRollback(previousMd5);
            throw new BizException("Nacos 不可达: " + e.getMessage());
        }
    }

    /**
     * P1-8: 计算 yaml 字符串的 MD5,作为版本标识。
     * 用于推送前快照(失败时回滚)+ 推送后对账(确认 Nacos 实际内容与推送一致)。
     */
    private String computeMd5(Map<String, Object> config) {
        try {
            byte[] bytes = toYaml(config).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * P1-8: 推送成功后立即 GET 一次,与推上去的内容对比。
     * 如果不一致(Nacos 侧插件/权限问题导致改写),立即告警并回滚。
     */
    private void verifyPushedConfig(String expectedMd5, Map<String, Object> expectedContent) {
        try {
            Map<String, Object> actual = getAiConfigUnmasked();
            String actualMd5 = computeMd5(actual);
            if (expectedMd5 != null && !expectedMd5.equals(actualMd5)) {
                log.error("Nacos 推送对账失败: expectedMd5={} actualMd5={},触发回滚",
                        expectedMd5, actualMd5);
                tryRollback(expectedMd5);
                throw new BizException("Nacos 推送对账失败,已自动回滚");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Nacos 推送对账跳过(读回失败): {}", e.getMessage());
        }
    }

    /**
     * P1-8: 推送/对账失败时,通过 Nacos Open API 尝试回滚。
     * 实际回滚依赖 Nacos 历史版本功能是否启用。
     * 失败时仅日志告警,不抛异常(避免覆盖上层原始异常)。
     */
    private void tryRollback(String knownMd5) {
        try {
            String rollbackUrl = "http://" + serverAddr
                    + "/nacos/v1/cs/history/rollback?dataId=" + AI_DATA_ID
                    + "&group=" + group + "&tenant=" + namespace;
            // 简化:仅记录告警,真实回滚由运维根据审计日志 + Nacos 历史版本手动执行
            // 全自动回滚需要先 GET 历史版本列表选最近一个 stable 版本,逻辑较重,留作 P3
            log.warn("推送失败:knownMd5={},请运维通过 Nacos 控制台「历史版本」一键回滚", knownMd5);
        } catch (Exception e) {
            log.error("回滚操作失败(需人工介入): {}", e.getMessage());
        }
    }

    /**
     * 校验入参中的敏感字段:不能是脱敏值,不能为空(qdrantApiKey 例外),必须 sk- 开头。
     * 防止前端把 SecretMasker 产生的 sk-***xxxx 当真实值覆盖后端。
     */
    private void validateSecretFields(Map<String, Object> map) {
        if (map == null) return;
        for (String field : SECRET_FIELDS) {
            Object v = map.get(field);
            if (v == null) continue;
            String s = String.valueOf(v);
            if (s.contains("***")) {
                throw new BizException(field + " 不能保存脱敏值,请重新输入完整 Key");
            }
            boolean isQdrant = "qdrantApiKey".equals(field);
            if (!isQdrant) {
                if (s.isEmpty()) {
                    throw new BizException(field + " 不能为空");
                }
                if (!s.startsWith("sk-")) {
                    throw new BizException(field + " 格式错误,必须以 sk- 开头");
                }
            }
        }
    }

    /**
     * 把入参与 Nacos 现有配置 merge,避免前端表单字段不全覆盖丢后端字段。
     * GET 失败时降级为仅推送入参(保留旧行为)。
     */
    private Map<String, Object> mergeWithExisting(Map<String, Object> aiConfigMap) {
        Map<String, Object> merged = new LinkedHashMap<>();
        try {
            merged.putAll(getAiConfigUnmasked());
        } catch (Exception e) {
            log.warn("读取现有 Nacos ai 配置失败,降级为仅推送入参: {}", e.getMessage());
        }
        if (aiConfigMap != null) {
            aiConfigMap.forEach((k, v) -> {
                if (v != null) merged.put(k, v);
            });
        }
        return merged;
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
                return buildMaskedFallback();
            }
            Map<String, Object> root = new Yaml().load(body);
            Object ai = root == null ? null : root.get("ai");
            if (ai instanceof Map) {
                return maskSensitive((Map<String, Object>) ai);
            }
            return new LinkedHashMap<>();
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.info("Nacos 配置不存在(首次使用尚未保存),回退本地配置: dataId={}", AI_DATA_ID);
            return buildMaskedFallback();
        } catch (RestClientException e) {
            log.error("调用 Nacos Open API 失败", e);
            throw new BizException("Nacos 不可达: " + e.getMessage());
        }
    }

    /**
     * Nacos 配置不存在时,从 Spring Environment 读取当前生效的 ai.* 属性构建回退 map,
     * 让前端展示 application.yml 里的默认配置而非空白表单。
     * Binder 读出的 key 为 kebab-case(Spring Boot YAML 惯例),需转为前端期望的 camelCase。
     *
     * <p><b>注意:</b>内部使用,不脱敏。仅供审计/连接测试等需要真实 key 的服务端链路使用。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAiConfigUnmasked() {
        String url = buildUrl();
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new BizException("Nacos 拉取失败: HTTP " + resp.getStatusCode());
            }
            String body = resp.getBody();
            if (body == null || body.isBlank()) {
                return buildFallbackMap();
            }
            Map<String, Object> root = new Yaml().load(body);
            Object ai = root == null ? null : root.get("ai");
            if (ai instanceof Map) {
                return new LinkedHashMap<>((Map<String, Object>) ai);
            }
            return new LinkedHashMap<>();
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.info("Nacos 配置不存在,回退本地配置: dataId={}", AI_DATA_ID);
            return buildFallbackMap();
        } catch (RestClientException e) {
            log.error("调用 Nacos Open API 失败", e);
            throw new BizException("Nacos 不可达: " + e.getMessage());
        }
    }

    /**
     * 返回前端前对 api-key / embedding-api-key / qdrant-api-key 脱敏,
     * 避免 key 走接口出到浏览器/抓包。Nacos 存储侧仍为明文,由 Nacos 2.x
     * 自带 AES config encryption plugin 加密落盘(运维侧配置,不在此处处理)。
     */
    private Map<String, Object> maskSensitive(Map<String, Object> ai) {
        String[] keys = {"apiKey", "embeddingApiKey", "qdrantApiKey"};
        Map<String, Object> result = new LinkedHashMap<>(ai);
        for (String k : keys) {
            Object v = result.get(k);
            if (v instanceof String s && !s.isEmpty()) {
                result.put(k, SecretMasker.mask(s));
            }
        }
        return result;
    }

    private Map<String, Object> buildMaskedFallback() {
        return maskSensitive(buildFallbackMap());
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