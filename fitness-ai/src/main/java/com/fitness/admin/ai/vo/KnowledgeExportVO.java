package com.fitness.admin.ai.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.format.DateTimeFormat;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库导出 VO(Excel 行模型)
 * 与 KnowledgeImportRow 字段对齐(便于"导出后修改再导入"往返)
 */
@Data
public class KnowledgeExportVO {

    @ExcelProperty("ID")
    @ColumnWidth(8)
    private Long id;

    @ExcelProperty("分类")
    @ColumnWidth(12)
    private String category;

    @ExcelProperty("标题")
    @ColumnWidth(30)
    private String title;

    @ExcelProperty("内容")
    @ColumnWidth(60)
    private String content;

    @ExcelProperty("标签")
    @ColumnWidth(20)
    private String tags;

    @ExcelProperty("来源")
    @ColumnWidth(20)
    private String source;

    @ExcelProperty("向量状态")
    @ColumnWidth(10)
    private String vectorStatus;

    @ExcelProperty("Embedding模型")
    @ColumnWidth(20)
    private String vectorModel;

    @ExcelProperty("索引完成时间")
    @ColumnWidth(20)
    @DateTimeFormat("yyyy-MM-dd HH:mm:ss")
    private LocalDateTime vectorIndexedAt;

    @ExcelProperty("索引错误")
    @ColumnWidth(30)
    private String vectorError;

    @ExcelProperty("创建时间")
    @ColumnWidth(20)
    @DateTimeFormat("yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @ExcelProperty("更新时间")
    @ColumnWidth(20)
    @DateTimeFormat("yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
