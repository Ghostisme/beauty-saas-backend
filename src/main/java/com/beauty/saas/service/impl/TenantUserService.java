package com.beauty.saas.service.impl;

import com.beauty.saas.dto.LoginRequest;
import com.beauty.saas.dto.LoginResponse;
import com.beauty.saas.iam.IdentityService;
import com.beauty.saas.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TenantUserService implements UserService {
    private final IdentityService identity;
    @Override public LoginResponse login(LoginRequest request) { return identity.login(request); }
}
