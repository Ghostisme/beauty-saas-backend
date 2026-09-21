package com.beauty.saas.service;

import com.beauty.saas.dto.LoginRequest;
import com.beauty.saas.dto.LoginResponse;

/**
 * 用户服务接口
 *
 * @author Beauty SaaS Team
 */
public interface UserService {

    /**
     * 用户登录
     *
     * @param request 登录请求
     * @return 登录响应（包含token和用户信息）
     */
    LoginResponse login(LoginRequest request);
}
