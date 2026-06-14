package com.fitness.admin.user.service;

import cn.dev33.satoken.stp.StpInterface;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限数据加载实现。
 * 框架通过此组件获取当前用户的权限列表，使 @SaCheckPermission 注解生效。
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final AuthService authService;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        try {
            Long userId = Long.parseLong(String.valueOf(loginId));
            return authService.getPermissionsByUserId(userId);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // 当前权限模型基于 permissions 而非 roles，返回空列表即可
        return Collections.emptyList();
    }
}
