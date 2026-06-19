package com.fitness.admin.ai.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;

import java.util.List;

/**
 * 知识库批量导入行(JSON / CSV / XLSX 通用)
 * 与前端 list.vue "批量导入" 上传文件配套使用
 */
@Data
public class KnowledgeImportRow {

    /** 分类: training / recovery / nutrition / injury / sleep */
    @ExcelProperty("分类")
    private String category;

    @ExcelProperty("标题")
    private String title;

    @ExcelProperty("内容")
    private String content;

    @ExcelProperty("标签")
    private String tags;

    @ExcelProperty("来源")
    private String source;

    public List<String> parseTags() {
        if (tags == null || tags.isBlank()) return List.of();
        String t = tags.trim();
        // 支持 JSON 数组字符串 ["a","b"] 或 逗号/顿号分隔 a,b、a、b
        if (t.startsWith("[") && t.endsWith("]")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
                return m.readValue(t, new TypeReference<List<String>>() {});
            } catch (Exception ignored) {
                // fall through
            }
        }
        String[] parts = t.split("[,，、;；\\s]+");
        List<String> result = new java.util.ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }
}
