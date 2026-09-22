package com.beauty.saas.security;

import com.beauty.saas.common.ApiException;
import java.util.*;

public record AccountPrincipal(long tenantId, long userId, long authVersion, boolean owner, boolean platformAdmin, Map<String,Set<Long>> scopes) {
    public boolean global(String permission) { return platformAdmin || owner || scopes.getOrDefault(permission, Set.of()).contains(0L); }
    public boolean can(String permission) { return platformAdmin || owner || !scopes.getOrDefault(permission, Set.of()).isEmpty(); }
    public boolean can(String permission, long departmentId) { return global(permission) || scopes.getOrDefault(permission, Set.of()).contains(departmentId); }
    public void require(String permission) { if (!can(permission)) throw new ApiException(403, "没有操作权限，请联系企业管理员"); }
    public void requireGlobal(String permission) { if (!global(permission)) throw new ApiException(403, "此操作需要企业范围权限"); }
    public void requireDepartment(String permission, long departmentId) { if (!can(permission, departmentId)) throw new ApiException(403, "没有该部门/门店的操作权限"); }
    public void requirePlatform() { if (!platformAdmin) throw new ApiException(403, "仅平台超级管理员可操作"); }
    public AccountPrincipal inTenant(long id) { requirePlatform(); return new AccountPrincipal(id,userId,authVersion,false,true,scopes); }
}
