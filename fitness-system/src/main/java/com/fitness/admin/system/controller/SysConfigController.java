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
import com.fitness.admin.user.entity.AdminUser;
import com.fitness.admin.user.mapper.AdminUserMapper;
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
    private final AdminUserMapper adminUserMapper;

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
            String operatorName = currentUsername(operatorId);
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
                rec.setOldValueMasked(formatValue(field, oldVal));
                rec.setNewValueMasked(formatValue(field, newVal));
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

    /**
     * 审计日志写入时的值格式化:
     *   - 密钥字段(apiKey / embeddingApiKey / qdrantApiKey):统一脱敏为 ***,避免日志泄露
     *   - 普通字段:保留原值,便于审计识别 true/false / 数字 / URL 等具体变更
     */
    private String formatValue(String field, Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        if (SECRET_FIELDS.contains(field)) {
            return SecretMasker.mask(s);
        }
        return s;
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

    /**
     * 优先返回 admin_user.nickname,空时回退 username,再空则用 "id=<userId>"。
     * StpUtil.getLoginId() 在 Sa-Token 默认配置下返回的是 userId 而不是登录名,
     * 因此历史日志会出现 operatorName="1" 这种无意义值;这里改成查库拿真名。
     */
    private String currentUsername(Long operatorId) {
        if (operatorId == null) return "system";
        try {
            AdminUser user = adminUserMapper.selectById(operatorId);
            if (user == null) return "id=" + operatorId;
            if (user.getNickname() != null && !user.getNickname().isBlank()) return user.getNickname();
            if (user.getUsername() != null && !user.getUsername().isBlank()) return user.getUsername();
            return "id=" + operatorId;
        } catch (Exception e) {
            log.warn("读取操作人 admin_user 失败,回退 id: {}", e.getMessage());
            return "id=" + operatorId;
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
        // LocalDateTime 默认序列化为 ISO 8601("2026-06-20T09:39:14"),运维看着别扭,
        // 统一格式化为 "yyyy-MM-dd HH:mm:ss"。原值已在 MySQL 完整保存,不影响二次分析。
        java.time.format.DateTimeFormatter FMT =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<Map<String, Object>> rows = result.getRecords().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("dataId", r.getDataId());
            m.put("field", r.getField());
            m.put("op", r.getOp());
            m.put("oldValueMasked", r.getOldValueMasked());
            m.put("newValueMasked", r.getNewValueMasked());
            m.put("keyFingerprint", r.getKeyFingerprint());
            m.put("operatorId", r.getOperatorId());
            m.put("operatorName", r.getOperatorName());
            m.put("clientIp", r.getClientIp());
            m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().format(FMT));
            return m;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", rows);
        data.put("total", result.getTotal());
        return R.ok(data);
    }
}
