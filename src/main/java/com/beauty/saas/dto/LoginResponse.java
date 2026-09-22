package com.beauty.saas.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 登录响应DTO
 *
 * @author Beauty SaaS Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** JWT令牌 */
    private String token;

    /** 用户信息 */
    private UserInfo userInfo;

    /**
     * 用户信息内部类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        /** 用户ID */
        private Long id;

        /** 用户名 */
        private String username;

        /** 昵称 */
        private String nickname;

        /** 头像 */
        private String avatar;

        private String phone;
        private Long tenantId;
        private String tenantCode;
        private String tenantName;
        private Boolean owner;
        private Boolean platformAdmin;
        private List<String> permissions;
    }
}
