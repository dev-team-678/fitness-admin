package com.fitness.admin.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员创建/编辑入参 DTO。
 *
 * <p>与 {@link com.fitness.admin.user.entity.AdminUser} 区分,只暴露业务可写字段,
 * 通过 {@link com.fitness.admin.system.mapper.AdminUserEntityMapper} 转 entity。
 * 显式忽略 id / password(由 service 加密) / status / deleted / lastLogin* 字段,
 * 防止 controller 入参覆盖篡改。
 *
 * <p>create 场景: username / password / nickname 必填
 * <br>update 场景: nickname 必填,username/password 留空表示不修改,@NotBlank 不能复用,
 *      故用 validation groups + controller 显式切换分组
 */
@Data
public class AdminUserUpsertDTO {

    /** 创建校验分组 */
    public interface Create {
    }

    /** 更新校验分组 */
    public interface Update {
    }

    private Long id;

    /** create 必填,update 留空表示不修改用户名 */
    @NotBlank(message = "用户名不能为空", groups = Create.class)
    @Size(max = 64, groups = {Create.class, Update.class})
    private String username;

    @NotBlank(message = "昵称不能为空", groups = {Create.class, Update.class})
    @Size(max = 64, groups = {Create.class, Update.class})
    private String nickname;

    /** 仅 create 时必填,update 时为空表示不修改密码。 */
    @NotBlank(message = "密码不能为空", groups = Create.class)
    @Size(max = 128, groups = {Create.class, Update.class})
    private String password;

    private String avatar;
    private String email;
    private String phone;
    private Long roleId;
    private Long userId;
}
