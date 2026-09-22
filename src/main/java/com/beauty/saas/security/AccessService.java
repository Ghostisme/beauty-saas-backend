package com.beauty.saas.security;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.iam.IamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import static com.beauty.saas.iam.IamRepository.id;
import static com.beauty.saas.iam.IamRepository.text;

@Service
@RequiredArgsConstructor
public class AccessService {
    public static final Set<String> COMPANY_PERMISSIONS = Set.of("tenant:read", "tenant:write", "users:write", "departments:write", "roles:read", "roles:write", "order-settings:read", "order-settings:write",
        "sms-settings:read", "sms-settings:write", "sms-records:read", "sms-records:write", "sms-billing:read", "sms-billing:write");
    private final IamRepository repo;
    private final HttpServletRequest request;
    public AccountPrincipal current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (!(principal instanceof AccountPrincipal actor)) throw new ApiException(401, "请先登录");
        return actor;
    }
    public AccountPrincipal currentTenant() {
        var actor=current();
        String header=request.getHeader("X-Tenant-Id");
        if (!actor.platformAdmin()) {
            if (header!=null && !header.equals(String.valueOf(actor.tenantId()))) throw new ApiException(403,"不能切换到其他企业");
            return actor;
        }
        long tenant;
        try { tenant=Long.parseLong(header); } catch (NumberFormatException e) { throw new ApiException(400,"请先选择要管理的企业"); }
        if (tenant<=0 || repo.count("SELECT COUNT(*) FROM sys_tenant WHERE id=?",tenant)==0) throw new ApiException(404,"企业不存在");
        return actor.inTenant(tenant);
    }
    public AccountPrincipal reload(AccountPrincipal actor) {
        var fresh=load(actor.platformAdmin()?0:actor.tenantId(),actor.userId(),actor.authVersion());
        return actor.platformAdmin() && actor.tenantId()>0 ? fresh.inTenant(actor.tenantId()) : fresh;
    }
    public AccountPrincipal load(long tenantId, long userId, long version) {
        if (tenantId==0) {
            var platform=repo.one("SELECT u.auth_version FROM sys_user u JOIN sys_platform_admin a ON a.user_id=u.id WHERE u.tenant_id=0 AND u.id=? AND u.status=1 AND u.deleted=0",userId);
            if (platform==null || id(platform,"authVersion")!=version) throw new ApiException(401,"平台登录已失效，请重新登录");
            Map<String,Set<Long>> scopes=new HashMap<>();
            repo.rows("SELECT code FROM sys_permission").forEach(p -> scopes.put(text(p,"code"),Set.of(0L)));
            scopes.put("platform:manage",Set.of(0L));
            return new AccountPrincipal(0,userId,version,false,true,scopes);
        }
        var user = repo.one("SELECT u.auth_version FROM sys_user u JOIN sys_tenant t ON t.id=u.tenant_id WHERE u.tenant_id=? AND u.id=? AND u.deleted=0 AND u.status=1 AND t.status=1", tenantId, userId);
        if (user == null || id(user,"authVersion") != version) throw new ApiException(401, "登录已失效，请重新登录");
        boolean owner = repo.count("SELECT COUNT(*) FROM sys_tenant_admin WHERE tenant_id=? AND user_id=?", tenantId,userId) > 0;
        Map<String,Set<Long>> scopes = new HashMap<>();
        if (owner) {
            repo.rows("SELECT code FROM sys_permission").forEach(p -> scopes.put(text(p,"code"), Set.of(0L)));
        } else {
            var departments = repo.rows("SELECT id,parent_id FROM sys_department WHERE tenant_id=? AND status=1", tenantId);
            var grants = repo.rows("SELECT p.code,ur.department_id FROM sys_user_role ur JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status=1 JOIN sys_role_permission rp ON rp.tenant_id=r.tenant_id AND rp.role_id=r.id JOIN sys_permission p ON p.id=rp.permission_id WHERE ur.tenant_id=? AND ur.user_id=? AND (ur.department_id=0 OR EXISTS(SELECT 1 FROM sys_user_department ud JOIN sys_department d ON d.tenant_id=ud.tenant_id AND d.id=ud.department_id AND d.status=1 WHERE ud.tenant_id=ur.tenant_id AND ud.user_id=ur.user_id AND ud.department_id=ur.department_id))",tenantId,userId);
            for (var grant : grants) {
                String code = text(grant,"code"); long dept = id(grant,"departmentId");
                if (dept != 0 && COMPANY_PERMISSIONS.contains(code)) continue;
                Set<Long> allowed = scopes.computeIfAbsent(code, ignored -> new HashSet<>());
                allowed.add(dept);
                boolean changed;
                do { changed = false; for (var d : departments) if (allowed.contains(id(d,"parentId")) && id(d,"parentId") != 0) changed |= allowed.add(id(d,"id")); } while (changed);
            }
        }
        return new AccountPrincipal(tenantId,userId,version,owner,false,scopes);
    }
}
