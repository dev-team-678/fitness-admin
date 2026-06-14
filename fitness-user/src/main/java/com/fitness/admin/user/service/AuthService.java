package com.fitness.admin.user.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.admin.common.enums.ResultCodeEnum;
import com.fitness.admin.common.event.LoginEvent;
import com.fitness.admin.common.exception.BizException;
import com.fitness.admin.user.dto.LoginRequest;
import com.fitness.admin.user.dto.LoginResponse;
import com.fitness.admin.user.entity.AdminUser;
import com.fitness.admin.user.mapper.AdminRoleQueryMapper;
import com.fitness.admin.user.mapper.AdminUserMapper;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.DigestUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AdminUserMapper adminUserMapper;
    private final AdminRoleQueryMapper adminRoleQueryMapper;
    private final ApplicationEventPublisher eventPublisher;

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    public LoginResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String ip = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        AdminUser user = adminUserMapper.selectByUsername(request.getUsername());
        if (user == null) {
            eventPublisher.publishEvent(new LoginEvent(null, request.getUsername(), ip, userAgent, 2, "用户不存在"));
            throw new BizException(ResultCodeEnum.USER_NOT_FOUND);
        }
        if (user.getStatus() != 1) {
            eventPublisher.publishEvent(new LoginEvent(user.getId(), user.getUsername(), ip, userAgent, 2, "用户已禁用"));
            throw new BizException(ResultCodeEnum.USER_DISABLED);
        }
        String encryptPassword = DigestUtil.md5Hex(request.getPassword());
        if (!encryptPassword.equals(user.getPassword())) {
            eventPublisher.publishEvent(new LoginEvent(user.getId(), user.getUsername(), ip, userAgent, 2, "密码错误"));
            throw new BizException(ResultCodeEnum.USER_PASSWORD_ERROR);
        }

        StpUtil.login(user.getId());

        // 更新最后登录信息
        AdminUser update = new AdminUser();
        update.setId(user.getId());
        update.setLastLoginIp(ip);
        update.setLastLoginTime(LocalDateTime.now());
        adminUserMapper.updateById(update);

        // 发布成功登录事件
        eventPublisher.publishEvent(new LoginEvent(user.getId(), user.getUsername(), ip, userAgent, 1, null));

        LoginResponse response = new LoginResponse();
        response.setToken(StpUtil.getTokenValue());
        response.setExpire(StpUtil.getTokenTimeout());

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo();
        userInfo.setId(user.getId());
        userInfo.setUsername(user.getUsername());
        userInfo.setNickname(user.getNickname());
        userInfo.setAvatar(user.getAvatar());
        response.setUserInfo(userInfo);

        return response;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    public void logout() {
        StpUtil.logout();
    }

    public LoginResponse refreshToken() {
        StpUtil.renewTimeout(7200);
        LoginResponse response = new LoginResponse();
        response.setToken(StpUtil.getTokenValue());
        response.setExpire(StpUtil.getTokenTimeout());
        return response;
    }

    public void changePassword(String oldPassword, String newPassword) {
        Long userId = StpUtil.getLoginIdAsLong();
        AdminUser user = adminUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCodeEnum.USER_NOT_FOUND);
        }
        String encryptOld = DigestUtil.md5Hex(oldPassword);
        if (!encryptOld.equals(user.getPassword())) {
            throw new BizException(ResultCodeEnum.USER_PASSWORD_ERROR);
        }
        AdminUser update = new AdminUser();
        update.setId(userId);
        update.setPassword(DigestUtil.md5Hex(newPassword));
        adminUserMapper.updateById(update);
    }

    public LoginResponse.UserInfo getCurrentUserInfo() {
        Long userId = StpUtil.getLoginIdAsLong();
        AdminUser user = adminUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCodeEnum.USER_NOT_FOUND);
        }

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo();
        userInfo.setId(user.getId());
        userInfo.setUsername(user.getUsername());
        userInfo.setNickname(user.getNickname());
        userInfo.setAvatar(user.getAvatar());

        // 根据角色查询真实权限
        userInfo.setPermissions(resolvePermissions(user.getRoleId()));

        return userInfo;
    }

    /**
     * 根据角色 ID 查询权限列表。
     * roleId 为空或角色不存在时返回空列表；
     * super_admin 角色返回通配符 ["*"]。
     */
    private List<String> resolvePermissions(Long roleId) {
        if (roleId == null) {
            return Collections.emptyList();
        }
        String raw = adminRoleQueryMapper.selectPermissionsByRoleId(roleId);
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<String> perms = JSON_MAPPER.readValue(raw, STRING_LIST_TYPE);
            // 如果权限列表中包含通配符，直接返回 ["*"]
            if (perms != null && perms.contains("*")) {
                return List.of("*");
            }
            return perms != null ? perms : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 供 StpInterface 实现调用：根据用户 ID 获取权限列表。
     */
    public List<String> getPermissionsByUserId(Long userId) {
        AdminUser user = adminUserMapper.selectById(userId);
        if (user == null) {
            return Collections.emptyList();
        }
        return resolvePermissions(user.getRoleId());
    }
}
