package com.fitness.admin.ai.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.fitness.admin.ai.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /**
     * 导出全量/筛选后的知识列表(不分页,避免 Page 性能开销)
     * Wrapper 由 Service 构造(支持 category / vectorStatus / 关键词模糊)
     */
    List<KnowledgeBase> selectExportList(@Param(Constants.WRAPPER) Wrapper<KnowledgeBase> wrapper);
}
