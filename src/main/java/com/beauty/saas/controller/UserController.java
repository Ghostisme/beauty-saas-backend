package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.dto.LoginRequest;
import com.beauty.saas.dto.LoginResponse;
import com.beauty.saas.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器
 *
 * @author Beauty SaaS Team
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 用户登录
     *
     * @param request 登录请求
     * @return 统一响应结果
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Validated @RequestBody LoginRequest request) {
        return Result.success("登录成功", userService.login(request));
    }
}
