package com.beauty.saas.iam;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.*;
import com.beauty.saas.security.*;
import com.beauty.saas.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import static com.beauty.saas.iam.IamRepository.*;

@Service
@RequiredArgsConstructor
public class IdentityService {
    private final IamRepository repo;
    private final Passwords passwords;
    private final AccessService access;
    private final JwtUtil jwt;
    @Value("${platform.provisioning-key:}") private String provisioningKey;

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String code=Objects.toString(request.getTenantCode(),"").trim().toLowerCase(Locale.ROOT);
        boolean platform="PLATFORM".equals(request.getLoginType()) || request.getLoginType()==null && code.isEmpty();
        if (platform && !code.isEmpty()) throw new ApiException(400,"平台登录无需企业编码，请使用平台登录入口");
        if (!platform && code.isEmpty()) throw new ApiException(400,"请输入企业编码");
        var user = platform ? repo.one("SELECT u.* FROM sys_user u JOIN sys_platform_admin a ON a.user_id=u.id WHERE u.tenant_id=0 AND u.username=? AND u.status=1 AND u.deleted=0",request.getUsername().trim())
            : repo.one("SELECT u.* FROM sys_user u JOIN sys_tenant t ON t.id=u.tenant_id WHERE t.code=? AND t.status=1 AND u.username=? AND u.status=1 AND u.deleted=0",code,request.getUsername().trim());
        boolean matches = passwords.matches(request.getPassword(), user == null ? null : text(user,"password"));
        if (user == null || !matches) throw new ApiException(401,platform?"平台账号或密码错误":"企业编码、账号或密码错误");
        long tenantId=id(user,"tenantId"), userId=id(user,"id"), version=id(user,"authVersion");
        if (passwords.legacy(text(user,"password"))) {
            String upgraded=passwords.upgradeLegacy(request.getPassword());
            if (upgraded!=null) repo.update("UPDATE sys_user SET password=? WHERE tenant_id=? AND id=? AND password=?",upgraded,tenantId,userId,text(user,"password"));
        }
        AccountPrincipal principal=access.load(tenantId,userId,version);
        return new LoginResponse(jwt.generateToken(userId,tenantId,version), profile(principal));
    }

    public LoginResponse.UserInfo profile(AccountPrincipal principal) {
        var row=repo.one("SELECT u.id,u.username,u.nickname,u.phone,u.avatar,t.id tenant_id,t.code tenant_code,t.name tenant_name FROM sys_user u LEFT JOIN sys_tenant t ON t.id=u.tenant_id WHERE u.tenant_id=? AND u.id=?",principal.platformAdmin()?0:principal.tenantId(),principal.userId());
        LoginResponse.UserInfo result=new LoginResponse.UserInfo();
        result.setId(id(row,"id")); result.setUsername(text(row,"username")); result.setNickname(text(row,"nickname"));
        result.setPhone(text(row,"phone")); result.setAvatar(text(row,"avatar")); result.setTenantId(principal.platformAdmin()?0:principal.tenantId());
        result.setTenantCode(principal.platformAdmin()?"":text(row,"tenantCode")); result.setTenantName(principal.platformAdmin()?"余乐圈平台管理中心":text(row,"tenantName"));
        result.setPlatformAdmin(principal.platformAdmin());
        result.setOwner(principal.owner()); result.setPermissions(principal.scopes().keySet().stream().sorted().toList());
        return result;
    }

    @Transactional
    public Map<String,Object> provision(String suppliedKey, IamRequests.TenantCreate request) {
        long platformUser=0;
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication!=null && authentication.getPrincipal() instanceof AccountPrincipal actor) {
            actor.requirePlatform();
            repo.one("SELECT id FROM sys_user WHERE id=? AND tenant_id=0 FOR UPDATE",actor.userId());
            access.reload(actor).requirePlatform(); platformUser=actor.userId();
        } else {
            // Retained for trusted automation only. The browser uses a platform login, never this secret.
            if (provisioningKey.isBlank() || provisioningKey.getBytes(StandardCharsets.UTF_8).length < 32) throw new ApiException(503,"平台开通凭证需配置为至少 32 字节，请联系平台运维");
            if (suppliedKey == null || !MessageDigest.isEqual(provisioningKey.getBytes(StandardCharsets.UTF_8),suppliedKey.getBytes(StandardCharsets.UTF_8))) throw new ApiException(403,"请使用平台超级管理员登录");
        }
        long tenant=repo.insert("INSERT INTO sys_tenant(code,name) VALUES(?,?)",request.code(),request.name().trim());
        String[][] roles={{"ADMIN","管理员"},{"STORE_MANAGER","店长"},{"HOST","主理人"},{"FRONT_DESK","前台"},{"BEAUTICIAN","美容师"},{"EMPLOYEE","员工"},{"CUSTOMER","顾客"}};
        for (String[] role : roles) {
            long roleId=repo.insert("INSERT INTO sys_role(tenant_id,code,name,builtin) VALUES(?,?,?,1)",tenant,role[0],role[1]);
            if (role[0].equals("ADMIN")) {
                repo.update("INSERT INTO sys_role_permission(tenant_id,role_id,permission_id) SELECT ?,?,id FROM sys_permission",tenant,roleId);
            } else repo.update("INSERT INTO sys_role_permission(tenant_id,role_id,permission_id) SELECT ?,?,id FROM sys_permission WHERE code='home:read'",tenant,roleId);
        }
        String username=request.adminUsername()==null?"admin":request.adminUsername();
        long user=createEnterpriseAdmin(tenant,username,request.adminName(),request.adminPassword(),request.phone());
        repo.update("INSERT INTO sys_platform_audit(platform_user_id,tenant_id,action,detail) VALUES(?,?,'CREATE_ENTERPRISE',?)",platformUser,tenant,"开通企业 "+request.code()+"，企业管理员 "+username);
        return Map.of("tenantId",tenant,"tenantCode",request.code(),"adminUserId",user,"adminUsername",username);
    }

    long createEnterpriseAdmin(long tenant,String username,String name,String password,String phone) {
        if (repo.count("SELECT COUNT(*) FROM sys_tenant_admin WHERE tenant_id=?",tenant)>0) throw new ApiException(409,"此企业已开通管理员");
        var role=repo.one("SELECT id FROM sys_role WHERE tenant_id=? AND code='ADMIN' AND status=1",tenant);
        if (role==null) throw new ApiException(409,"企业管理员角色未初始化");
        long user=repo.insert("INSERT INTO sys_user(tenant_id,username,password,nickname,phone) VALUES(?,?,?,?,?)",tenant,username,passwords.hash(password),name.trim(),phone);
        repo.update("INSERT INTO sys_tenant_admin(tenant_id,user_id) VALUES(?,?)",tenant,user);
        repo.update("INSERT INTO sys_user_role(tenant_id,user_id,role_id,department_id) VALUES(?,?,?,0)",tenant,user,id(role,"id"));
        return user;
    }

    @Transactional
    public void changePassword(IamRequests.PasswordChange request) {
        AccountPrincipal actor=access.current();
        if (actor.platformAdmin()) repo.one("SELECT id FROM sys_user WHERE tenant_id=0 AND id=? FOR UPDATE",actor.userId());
        else repo.one("SELECT id FROM sys_tenant WHERE id=? FOR UPDATE",actor.tenantId());
        access.reload(actor);
        var row=repo.one("SELECT password FROM sys_user WHERE tenant_id=? AND id=? AND deleted=0",actor.tenantId(),actor.userId());
        if (!passwords.matches(request.currentPassword(),text(row,"password"))) throw new ApiException(400,"当前密码不正确");
        repo.update("UPDATE sys_user SET password=?,auth_version=auth_version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",passwords.hash(request.newPassword()),actor.tenantId(),actor.userId());
    }
}
