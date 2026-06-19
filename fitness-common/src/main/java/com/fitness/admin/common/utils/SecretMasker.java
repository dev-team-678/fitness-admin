package com.fitness.admin.common.utils;

/**
 * 敏感字段脱敏工具。
 * <p>规则:保留前 4 后 4,中间用 *** 替代;长度 <= 8 直接全部 ***。
 * 用于 api-key / embedding-api-key / qdrant-api-key 等密钥字段返回前端时脱敏。
 */
public final class SecretMasker {

    private SecretMasker() {}

    public static String mask(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (raw.length() <= 8) {
            return "***";
        }
        return raw.substring(0, 4) + "***" + raw.substring(raw.length() - 4);
    }
}
