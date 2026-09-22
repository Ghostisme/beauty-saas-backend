package com.beauty.saas.iam;

import com.beauty.saas.security.Passwords;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** New installations only: no shared default password, and never reset an existing platform account. */
@Component
@RequiredArgsConstructor
public class PlatformBootstrap implements ApplicationRunner {
    private final IamRepository repo;
    private final Passwords passwords;
    @Value("${platform.bootstrap-password:}") private String initialPassword;
    @Override @Transactional public void run(ApplicationArguments args) {
        if (initialPassword.isBlank() || repo.count("SELECT COUNT(*) FROM sys_platform_admin")>0) return;
        long user=repo.insert("INSERT INTO sys_user(tenant_id,username,password,nickname) VALUES(0,'admin',?,'超级管理员')",passwords.hash(initialPassword));
        repo.update("INSERT INTO sys_platform_admin(user_id) VALUES(?)",user);
        repo.update("INSERT INTO sys_platform_audit(platform_user_id,tenant_id,action,detail) VALUES(?,0,'BOOTSTRAP_PLATFORM_ADMIN','通过部署环境初始化平台超级管理员')",user);
    }
}
