package com.fitness.admin.ai.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * API Key 池条目 (P2-9 Key 轮转,2026-06-20)。
 *
 * <p>一个条目对应一把 key,带:
 * <ul>
 *   <li>{@code id}      — 该 key 在池中的稳定标识(用于审计/熔断状态跟踪)</li>
 *   <li>{@code value}   — 实际 key(加载到内存后是明文,持久化到 Nacos 时建议走 P1-5 AES 落盘加密)</li>
 *   <li>{@code role}    — primary / secondary / disabled</li>
 *   <li>{@code priority}— 数字越小优先级越高,选路时按 priority ASC 遍历</li>
 *   <li>{@code label}   — 可读备注(便于运维识别"哪个 key 对应哪个阿里云账号")</li>
 * </ul>
 *
 * <p>为兼容 P0 / P1 期间的扁平配置 {@code apiKey} / {@code embeddingApiKey},
 * AiConfig 仍保留这两个 String 字段作为"primary key"进入;
 * 只有当用户主动配置了多 key (YAML 中 {@code apiKeys: [{...}, {...}]}) 时,
 * 才走 {@link com.fitness.admin.ai.security.ApiKeyManager} 的轮转逻辑。
 */
@Data
@NoArgsConstructor
public class ApiKeyEntry {

    public enum Role {
        PRIMARY,
        SECONDARY,
        DISABLED
    }

    private String id;
    private String value;
    private Role role = Role.PRIMARY;
    private int priority = 100;
    private String label;

    @JsonCreator
    public ApiKeyEntry(@JsonProperty("id") String id,
                       @JsonProperty("value") String value,
                       @JsonProperty("role") Role role,
                       @JsonProperty("priority") Integer priority,
                       @JsonProperty("label") String label) {
        this.id = id;
        this.value = value;
        if (role != null) this.role = role;
        if (priority != null) this.priority = priority;
        this.label = label;
    }

    public boolean isUsable() {
        return role != Role.DISABLED
                && value != null
                && !value.isBlank();
    }
}
