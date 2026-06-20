package com.fitness.admin.ai.config;

import com.fitness.admin.ai.security.ApiKeyEntry;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * AI服务配置
 *
 * <p>由 Nacos dataId <code>fitness-admin-ai.yaml</code> 注入,所有 ai.* 字段支持热刷新。
 * 字段命名保持 kebab-case → camelCase 映射(application.yml / Nacos 用前者,Java 用后者)。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
@RefreshScope
@Validated
public class AiConfig {

    /**
     * AI提供商：openai, claude, deepseek
     */
    @NotBlank
    @Pattern(regexp = "openai|claude|deepseek", message = "provider 仅支持 openai/claude/deepseek")
    private String provider = "openai";

    /**
     * API Key
     */
    @NotBlank
    private String apiKey;

    /**
     * API Key 池 (P2-9 Key 轮转,2026-06-20)。
     * 为空时 {@link com.fitness.admin.ai.security.ApiKeyManager} 自动回退到 {@link #apiKey} 单值。
     * 格式: [{id: "primary", value: "sk-...", role: PRIMARY, priority: 0}, ...]
     */
    private List<ApiKeyEntry> apiKeys = new ArrayList<>();

    /**
     * API Base URL
     */
    @NotBlank
    @Pattern(regexp = "^https?://.+", message = "apiBaseUrl 必须以 http(s):// 开头")
    private String apiBaseUrl = "https://api.openai.com/v1";

    /**
     * 模型名称
     */
    @NotBlank
    private String model = "gpt-3.5-turbo";

    /**
     * 最大token数
     */
    @NotNull
    @Min(1)
    @Max(32000)
    private Integer maxTokens = 1500;

    /**
     * 温度参数
     */
    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private Double temperature = 0.7;

    /**
     * AI 调用超时(秒),超过此时间未返回首字节则中断。
     * 默认为 60s,可被 ai.timeout-seconds 环境变量覆盖。
     */
    @NotNull
    @Min(1)
    @Max(600)
    private Integer timeoutSeconds = 60;

    /**
     * 单用户每分钟最大调用次数(限流)。0 表示不限制。
     */
    @Min(0)
    private Integer rateLimitPerMinute = 20;

    /**
     * 系统提示词
     */
    private String systemPrompt = "你是一个专业的AI健身助手，名叫FitBot。你擅长：\n" +
            "1. 制定个性化的训练计划\n" +
            "2. 解答健身动作和技巧问题\n" +
            "3. 提供营养和饮食建议\n" +
            "4. 分析训练数据和进展\n" +
            "5. 提供运动伤害预防建议\n\n" +
            "请用友好、专业的语气回答用户的问题。如果用户问到非健身相关的问题，礼貌地引导他们回到健身话题。";

    /**
     * 单次请求携带的历史消息条数(不含 system 提示词)。值越大上下文越丰富,
     * 但请求体越大、响应越慢,需权衡 token 成本与超时风险。默认 6。
     */
    @Min(0)
    @Max(50)
    private Integer chatHistoryLimit = 6;

    /**
     * 异步模式开关。开启后,对话请求会立即返回(消息状态=processing),
     * 真实 AI 调用放入后台线程池执行,客户端通过轮询消息状态获取结果。
     * 解决长耗时 AI 调用拖慢 HTTP 线程、超时返回空响应的问题。
     */
    private Boolean asyncChatEnabled = true;

    /**
     * 异步模式下,后台 AI 调用的最大等待时间(秒)。超过则写入 FAILED 状态。
     */
    @Min(1)
    @Max(600)
    private Integer asyncChatTimeoutSeconds = 90;

    /**
     * Embedding 专用 API Base URL(可与聊天 API 分开),如 DashScope:
     * https://dashscope.aliyuncs.com/compatible-mode/v1
     * 为空时回退到 apiBaseUrl。
     */
    @Pattern(regexp = "^(https?://.+)?$", message = "embeddingApiBaseUrl 必须以 http(s):// 开头或为空")
    private String embeddingApiBaseUrl;

    /**
     * Embedding 专用 API Key。为空时回退到 apiKey。
     */
    private String embeddingApiKey;

    /**
     * Embedding Key 池 (P2-9 Key 轮转,2026-06-20)。
     * 为空时 {@link com.fitness.admin.ai.security.EmbeddingApiKeyManager} 自动回退到 {@link #embeddingApiKey} 单值。
     */
    private List<ApiKeyEntry> embeddingApiKeys = new ArrayList<>();

    /**
     * Embedding 模型名,如 text-embedding-v3(DashScope)或 text-embedding-3-small(OpenAI)
     */
    @NotBlank
    private String embeddingModel = "text-embedding-v3";

    /**
     * Embedding 维度,需与 Qdrant collection 一致; DashScope text-embedding-v3 最大 1024
     */
    @NotNull
    @Min(64)
    @Max(4096)
    private Integer embeddingDimension = 1024;

    /**
     * Rerank 开关(预留)。Rerank 服务暂未实现,字段先存,后续接入 CoHere / 阿里云通用排序。
     */
    private Boolean ragRerankEnabled = false;

    /**
     * RAG 知识来源白名单,仅索引列表内的 source。空列表 = 索引全部。
     * 可选值: exercise / nutrition / training / recovery / faq
     */
    private List<String> ragKnowledgeSources = new ArrayList<>();

    /**
     * 微调规则白名单(config_key 列表)。
     * 仅当 key 在白名单中时,系统才允许写入 ai_adjustment_config。
     * 空列表 = 关闭白名单校验(危险,生产环境应配置)。
     */
    private List<String> adjustmentRuleWhitelist = new ArrayList<>(List.of(
            "weekly_progression_rate",
            "max_intensity_increase",
            "fatigue_threshold",
            "recovery_days_min",
            "volume_cap_per_session"
    ));

    /**
     * 单用户每日对话上限,0 表示不限制。
     */
    @Min(0)
    private Integer dailyChatLimit = 100;

    /**
     * 单用户每日计划生成上限,0 表示不限制。
     */
    @Min(0)
    private Integer dailyPlanLimit = 10;

    /**
     * 单日 Token 总量上限(全局),0 表示不限制。
     */
    @Min(0)
    private Long maxTokensPerDay = 0L;

    /**
     * 异步 AI 调用的并发上限,超过则排队。
     */
    @Min(1)
    @Max(128)
    private Integer concurrencyLimit = 10;

    /**
     * Qdrant gRPC 地址,如 localhost:6334
     */
    @NotBlank
    private String qdrantUrl = "localhost:6334";

    /**
     * Qdrant API Key(本地无密码可空)
     */
    private String qdrantApiKey = "";

    /**
     * Qdrant collection 名
     */
    @NotBlank
    private String qdrantCollection = "fitness_knowledge";

    /**
     * RAG 检索 Top-K
     */
    @Min(1)
    @Max(50)
    private Integer ragTopK = 5;

    /**
     * 相似度阈值,低于此分数丢弃(余弦距离 0~1)
     */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double ragMinScore = 0.6;

    /**
     * 切块大小(字符),中文按字符计数
     */
    @Min(100)
    @Max(5000)
    private Integer chunkSize = 500;

    /**
     * 切块重叠字符数
     */
    @Min(0)
    private Integer chunkOverlap = 50;

    /**
     * 获取 Embedding 实际使用的 API Base URL,优先用 embeddingApiBaseUrl,为空则回退到 apiBaseUrl。
     */
    public String getEffectiveEmbeddingApiBaseUrl() {
        return (embeddingApiBaseUrl != null && !embeddingApiBaseUrl.isBlank())
                ? embeddingApiBaseUrl : apiBaseUrl;
    }

    /**
     * 获取 Embedding 实际使用的 API Key,优先用 embeddingApiKey,为空则回退到 apiKey。
     */
    public String getEffectiveEmbeddingApiKey() {
        return (embeddingApiKey != null && !embeddingApiKey.isBlank())
                ? embeddingApiKey : apiKey;
    }
}
