package com.fitness.admin.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 轻量级角色权限查询 Mapper。
 * 仅用于 AuthService 查询用户角色对应的权限列表，避免引入 fitness-system 循环依赖。
 */
@Mapper
public interface AdminRoleQueryMapper {

    @Select("SELECT permissions FROM admin_role WHERE id = #{roleId}")
    String selectPermissionsByRoleId(Long roleId);
}
