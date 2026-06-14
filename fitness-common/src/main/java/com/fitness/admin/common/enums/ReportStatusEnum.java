package com.fitness.admin.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 举报状态枚举。code 与数据库 report.status 的 TINYINT 字段一一对应,
 * value 字段经 @JsonValue 序列化后输出给前端,与前端 statusMap key 对齐。
 */
@Getter
public enum ReportStatusEnum {

    PENDING(0, "pending", "待处理"),
    CONFIRMED(1, "resolved", "已处理"),
    DISMISSED(2, "dismissed", "已驳回");

    @EnumValue
    private final Integer code;

    @JsonValue
    private final String value;

    private final String desc;

    ReportStatusEnum(Integer code, String value, String desc) {
        this.code = code;
        this.value = value;
        this.desc = desc;
    }

    public static ReportStatusEnum of(Integer code) {
        if (code == null) {
            return null;
        }
        for (ReportStatusEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return null;
    }
}
