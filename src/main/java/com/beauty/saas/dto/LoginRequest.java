package com.beauty.saas.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.io.Serializable;

/**
 * 登录请求DTO
 *
 * @author Beauty SaaS Team
 */
@Data
public class LoginRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Size(max = 40)
    private String tenantCode;

    @Pattern(regexp = "PLATFORM|TENANT", message = "登录类型不正确")
    private String loginType;

    /** 用户名 */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 50)
    private String username;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    @Size(max = 128)
    private String password;
}
