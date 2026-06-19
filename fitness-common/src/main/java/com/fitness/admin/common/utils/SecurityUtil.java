package com.fitness.admin.common.utils;

import cn.dev33.satoken.stp.StpUtil;

import java.util.Collections;
import java.util.List;

public class SecurityUtil {

    public static Long getCurrentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isLogin() {
        return StpUtil.isLogin();
    }

    public static void checkLogin() {
        StpUtil.checkLogin();
    }

    /**
     * 当前登录用户的角色列表(已去掉 "ROLE_" 前缀,小写)。未登录返回空列表。
     */
    public static List<String> getCurrentUserRoles() {
        try {
            Object roles = StpUtil.getSession().get("role_list");
            if (roles instanceof List<?> list) {
                return list.stream().filter(String.class::isInstance)
                        .map(Object::toString)
                        .toList();
            }
        } catch (Exception ignored) {
            // 未登录 / 无 session
        }
        return Collections.emptyList();
    }

    /**
     * 是否拥有指定角色(不区分大小写,自动兼容 "ROLE_xxx" 与 "xxx" 两种命名)。
     */
    public static boolean hasRole(String role) {
        if (role == null || role.isBlank()) return false;
        String target = role.toLowerCase();
        if (target.startsWith("role_")) target = target.substring(5);
        final String normalizedTarget = target;
        return getCurrentUserRoles().stream()
                .map(String::toLowerCase)
                .map(r -> r.startsWith("role_") ? r.substring(5) : r)
                .anyMatch(r -> r.equals(normalizedTarget));
    }
}
