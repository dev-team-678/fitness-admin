package com.fitness.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.admin.common.base.BaseController;
import com.fitness.admin.common.exception.BizException;
import com.fitness.admin.common.result.R;
import com.fitness.admin.system.entity.SysConfig;
import com.fitness.admin.system.service.SysConfigService;
import com.fitness.admin.common.annotation.LogOperation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "系统配置")
@RestController
@RequestMapping("/sys-config")
@RequiredArgsConstructor
@SaCheckPermission("sys:config:read")
public class SysConfigController extends BaseController {

    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;

    private static final String AI_CONFIG_PREFIX = "ai.";

    @Operation(summary = "配置列表")
    @GetMapping("/list")
    public R<List<SysConfig>> list() {
        return success(sysConfigService.list());
    }

    @LogOperation(action = "新增", module = "系统配置")
    @Operation(summary = "保存配置")
    @PostMapping
    @SaCheckPermission("sys:config:create")
    public R<Void> save(@RequestBody SysConfig config) {
        sysConfigService.save(config);
        return success();
    }

    @LogOperation(action = "编辑", module = "系统配置")
    @Operation(summary = "按key更新配置")
    @PutMapping("/{configKey}")
    @SaCheckPermission("sys:config:update")
    public R<Void> updateByKey(@PathVariable String configKey, @RequestBody Map<String, Object> body) {
        String configValue = stringify(body.get("configValue"));
        String description = stringify(body.get("description"));
        sysConfigService.saveByKey(configKey, configValue, description);
        return success();
    }

    @LogOperation(action = "删除", module = "系统配置")
    @Operation(summary = "删除配置")
    @DeleteMapping("/{id}")
    @SaCheckPermission("sys:config:delete")
    public R<Void> delete(@PathVariable Long id) {
        sysConfigService.delete(id);
        return success();
    }

    @Operation(summary = "获取AI配置")
    @GetMapping("/ai-config")
    public R<Map<String, Object>> getAiConfig() {
        List<SysConfig> configs = sysConfigService.listByKeyPrefix(AI_CONFIG_PREFIX);
        Map<String, Object> result = new LinkedHashMap<>();
        for (SysConfig config : configs) {
            // 去掉前缀 "ai." 返回给前端
            String key = config.getConfigKey();
            if (key.startsWith(AI_CONFIG_PREFIX)) {
                key = key.substring(AI_CONFIG_PREFIX.length());
            }
            // 尝试还原原始类型(String/Number/Boolean/JSON数组/对象)
            result.put(key, parseValue(config.getConfigValue()));
        }
        return success(result);
    }

    @LogOperation(action = "编辑", module = "系统配置")
    @Operation(summary = "更新AI配置")
    @PutMapping("/ai-config")
    @SaCheckPermission("sys:config:update")
    public R<Void> updateAiConfig(@RequestBody Map<String, Object> configMap) {
        Map<String, String> toSave = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : configMap.entrySet()) {
            String fullKey = AI_CONFIG_PREFIX + entry.getKey();
            toSave.put(fullKey, stringify(entry.getValue()));
        }
        sysConfigService.saveByKeyBatch(toSave);
        return success();
    }

    @Operation(summary = "测试AI连接")
    @PostMapping("/ai-config/test-connection")
    @SaCheckPermission("sys:config:update")
    public R<Void> testAiConnection() {
        // TODO: 实际测试LLM连接
        return success();
    }

    /**
     * 把任意 value 转成 String 存 DB:
     *  - String: 原样
     *  - Number/Boolean: 调 toString
     *  - List/Map/Object: 序列化成 JSON
     *  - null: 存空字符串(避免 DB NOT NULL 报错)
     */
    private String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException("配置值序列化失败: " + e.getMessage());
        }
    }

    /**
     * 还原 DB 中的 String:
     *  - 看起来像 JSON 数组/对象 → 反序列化
     *  - 否则原样返回 String
     */
    private Object parseValue(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        if ((trimmed.startsWith("[") && trimmed.endsWith("]"))
                || (trimmed.startsWith("{") && trimmed.endsWith("}"))) {
            try {
                return objectMapper.readValue(trimmed, Object.class);
            } catch (JsonProcessingException ignored) {
                // 不是合法 JSON,降级为 String
            }
        }
        return raw;
    }
}
