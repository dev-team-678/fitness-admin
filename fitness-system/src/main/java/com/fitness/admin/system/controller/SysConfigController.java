package com.fitness.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitness.admin.ai.service.AiConnectionTestService;
import com.fitness.admin.common.annotation.LogOperation;
import com.fitness.admin.common.base.BaseController;
import com.fitness.admin.common.exception.BizException;
import com.fitness.admin.common.result.R;
import com.fitness.admin.common.utils.SecretMasker;
import com.fitness.admin.common.utils.SecurityUtil;
import com.fitness.admin.system.config.NacosConfigPublisher;
import com.fitness.admin.system.entity.AiConfigAuditLog;
import com.fitness.admin.system.entity.SysConfig;
import com.fitness.admin.system.mapper.AiConfigAuditLogMapper;
import com.fitness.admin.system.service.SysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Tag(name = "系统配置")
@RestController
@RequestMapping("/sys-config")
@RequiredArgsConstructor
@SaCheckPermission("sys:config:read")
public class SysConfigController extends BaseController {

    private final SysConfigService sysConfigService;
    private final NacosConfigPublisher nacosConfigPublisher;
    private final AiConnectionTestService aiConnectionTestService;
    private final AiConfigAuditLogMapper aiConfigAuditLogMapper;

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
        Object value = body.get("configValue");
        Object description = body.get("description");
        sysConfigService.saveByKey(configKey,
                value == null ? "" : String.valueOf(value),
                description == null ? null : String.valueOf(description));
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

    @Operation(summary = "获取AI配置(api-key 已脱敏,普通权限)")
    @GetMapping("/ai-config")
    public R<Map<String, Object>> getAiConfig() {
        return success(nacosConfigPublisher.getAiConfig());
    }

    @Operation(summary = "获取AI配置(完整 key,需要 sys:config:secret 权限)")
    @GetMapping("/ai-config/secret")
    @SaCheckPermission("sys:config:secret")
    public R<Map<String, Object>> getAiConfigSecret() {
        return success(nacosConfigPublisher.getAiConfigUnmasked());
    }

    @LogOperation(action = "编辑", module = "系统配置")
    @Operation(summary = "更新AI配置(非密钥字段只需 sys:config:update;包含密钥字段需要 sys:config:secret)")
    @PutMapping("/ai-config")
    @SaCheckPermission("sys:config:update")
    public R<Void> updateAiConfig(@RequestBody Map<String, Object> configMap,
                                  HttpServletRequest request) {
        // P0-4: 入参含密钥字段时,需 sys:config:secret 权限。
        // 注意:NacosConfigPublisher.mergeWithExisting 会在合并后写入,即便用户没传 apiKey,
        // 旧值仍会保留 — 所以这里以"入参含非 null 密钥字段"为准判定。
        if (containsSecretField(configMap) && !StpUtil.hasPermission("sys:config:secret")) {
            throw new BizException("修改 API Key 需要 sys:config:secret 权限");
        }
        Map<String, Object> previous;
        try {
            previous = nacosConfigPublisher.getAiConfigUnmasked();
        } catch (Exception e) {
            log.warn("读取旧 ai 配置用于审计失败,继续推送: {}", e.getMessage());
            previous = new HashMap<>();
        }
        nacosConfigPublisher.publishAiConfig(configMap);
        writeAuditLog(previous, configMap, request);
        return success();
    }

    private void writeAuditLog(Map<String, Object> oldMap, Map<String, Object> newMap,
                               HttpServletRequest request) {
        try {
            Long operatorId = SecurityUtil.getCurrentUserId();
            String operatorName = currentUsername();
            String clientIp = clientIp(request);
            for (Map.Entry<String, Object> e : newMap.entrySet()) {
                String field = e.getKey();
                Object oldVal = oldMap.get(field);
                Object newVal = e.getValue();
                if (Objects.equals(oldVal, newVal)) continue;
                AiConfigAuditLog rec = new AiConfigAuditLog();
                rec.setDataId(NacosConfigPublisher.AI_DATA_ID);
                rec.setField(field);
                rec.setOp("update");
                rec.setOldValueMasked(SecretMasker.mask(oldVal == null ? "" : String.valueOf(oldVal)));
                rec.setNewValueMasked(SecretMasker.mask(newVal == null ? "" : String.valueOf(newVal)));
                // P2-10: 对 key 字段额外算 SHA-256 前 8 位指纹,便于审计识别"哪个 key"
                if (SECRET_FIELDS.contains(field)) {
                    rec.setKeyFingerprint(fingerprint(newVal == null ? "" : String.valueOf(newVal)));
                }
                rec.setOperatorId(operatorId);
                rec.setOperatorName(operatorName);
                rec.setClientIp(clientIp);
                aiConfigAuditLogMapper.insert(rec);
            }
        } catch (Exception ex) {
            log.error("写 AI 配置审计日志失败", ex);
        }
    }

    private static final java.util.Set<String> SECRET_FIELDS =
            java.util.Set.of("apiKey", "embeddingApiKey", "qdrantApiKey");

    /**
     * P0-4: 入参 map 包含非 null 密钥字段时,需要 sys:config:secret 权限。
     * null 值不算"包含" — 表达"不修改该字段",可与"包含密钥"权限解耦。
     */
    private static boolean containsSecretField(Map<String, Object> map) {
        if (map == null) return false;
        for (String k : map.keySet()) {
            if (SECRET_FIELDS.contains(k) && map.get(k) != null) {
                return true;
            }
        }
        return false;
    }

    /** SHA-256 前 8 位十六进制字符串,用于在不暴露 key 本身的前提下区分"哪个 key" */
    private static String fingerprint(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentUsername() {
        try {
            Object loginId = StpUtil.getLoginId();
            return loginId == null ? "system" : String.valueOf(loginId);
        } catch (Exception e) {
            return "system";
        }
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        return ip;
    }

    @Operation(summary = "测试AI连接(真实调用 LLM)")
    @PostMapping("/ai-config/test-connection")
    @SaCheckPermission("sys:config:update")
    public R<Map<String, Object>> testAiConnection(@RequestBody(required = false) Map<String, Object> configOverride) {
        Map<String, Object> effective = new LinkedHashMap<>(nacosConfigPublisher.getAiConfigUnmasked());
        if (configOverride != null) {
            configOverride.forEach((k, v) -> { if (v != null) effective.put(k, v); });
        }
        return success(aiConnectionTestService.test(effective));
    }

    @Operation(summary = "AI配置变更审计日志")
    @GetMapping("/ai-config/audit-log")
    public R<Map<String, Object>> auditLog(@RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "20") Integer pageSize) {
        Page<AiConfigAuditLog> page = new Page<>(pageNum, pageSize);
        IPage<AiConfigAuditLog> result = aiConfigAuditLogMapper.selectPage(page,
                new LambdaQueryWrapper<AiConfigAuditLog>()
                        .orderByDesc(AiConfigAuditLog::getCreatedAt));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        return R.ok(data);
    }
}
