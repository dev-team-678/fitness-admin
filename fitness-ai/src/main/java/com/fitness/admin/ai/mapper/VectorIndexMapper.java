package com.fitness.admin.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitness.admin.ai.entity.VectorIndex;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VectorIndexMapper extends BaseMapper<VectorIndex> {

    @Delete("DELETE FROM vector_index WHERE source_type = #{sourceType} AND source_id = #{sourceId}")
    int deleteBySource(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);
}
