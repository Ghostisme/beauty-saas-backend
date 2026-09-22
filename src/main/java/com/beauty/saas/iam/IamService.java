package com.beauty.saas.iam;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.IamRequests.*;
import com.beauty.saas.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.beauty.saas.iam.IamRepository.*;

@Service
@RequiredArgsConstructor
public class IamService {
    private final IamRepository repo;
    private final AccessService access;
    private final Passwords passwords;

    private AccountPrincipal require(String permission) { var actor=access.currentTenant(); actor.require(permission); return actor; }
    /** Serialize association writes within the tenant, preventing create/delete races without foreign keys. */
    private AccountPrincipal write(String permission, boolean companyWide) {
        var actor=require(permission);
        if (companyWide) actor.requireGlobal(permission);
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? AND status=1 FOR UPDATE",actor.tenantId()) == null) throw new ApiException(409,"企业已停用，请先由平台启用");
        // Recheck after acquiring the lock, in case a concurrent request revoked this account.
        var fresh=access.reload(actor);
        fresh.require(permission);
        if (companyWide) fresh.requireGlobal(permission);
        if (fresh.platformAdmin()) repo.update("INSERT INTO sys_platform_audit(platform_user_id,tenant_id,action,detail) VALUES(?,?,?,?)",fresh.userId(),fresh.tenantId(),"MANAGE_"+permission,"平台维护企业范围数据");
        return fresh;
    }
    private Map<String,Object> entity(String table, long tenant, long recordId) {
        if (!Set.of("sys_user","sys_department","sys_room","sys_role").contains(table)) throw new IllegalArgumentException("Unsupported table");
        var row=repo.one("SELECT * FROM " + table + " WHERE tenant_id=? AND id=?" + (table.equals("sys_user") ? " AND deleted=0" : ""),tenant,recordId);
        if (row == null) throw new ApiException(404,"记录不存在或不属于当前企业");
        return row;
    }
    private String allowed(AccountPrincipal actor, String permission, String column, List<Object> args) {
        if (actor.global(permission)) return "";
        var ids=actor.scopes().getOrDefault(permission,Set.of());
        if (ids.isEmpty()) return " AND 1=0";
        args.addAll(ids);
        return " AND " + column + " IN (" + marks(ids.size()) + ")";
    }
    private static String marks(int size) { return String.join(",",Collections.nCopies(size,"?")); }
    private void page(int page, int pageSize) { if (page<1 || page>100000 || pageSize<1 || pageSize>100) throw new ApiException(400,"分页参数不正确，每页最多 100 条"); }
    private String keyword(String input) {
        String value=input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (value.length()>100) throw new ApiException(400,"搜索内容不能超过 100 字");
        return "%" + value.replace("!","!!").replace("%","!%").replace("_","!_") + "%";
    }
    private void activeDepartment(long tenant, long department) {
        if (id(entity("sys_department",tenant,department),"status") != 1) throw new ApiException(409,"部门/门店已停用");
    }
    private boolean owner(long tenant, long user) { return repo.count("SELECT COUNT(*) FROM sys_tenant_admin WHERE tenant_id=? AND user_id=?",tenant,user)>0; }

    public Map<String,Object> tenant() {
        var actor=require("tenant:read"); actor.requireGlobal("tenant:read");
        return repo.one("SELECT t.id,t.code,t.name,t.status,t.create_time,u.username admin_username,u.nickname admin_name FROM sys_tenant t LEFT JOIN sys_tenant_admin a ON a.tenant_id=t.id LEFT JOIN sys_user u ON u.tenant_id=a.tenant_id AND u.id=a.user_id WHERE t.id=?",actor.tenantId());
    }
    @Transactional public void saveTenant(TenantSave request) {
        var actor=write("tenant:write",true);
        repo.update("UPDATE sys_tenant SET name=?,update_time=CURRENT_TIMESTAMP WHERE id=?",request.name().trim(),actor.tenantId());
    }

    public List<Map<String,Object>> departments() {
        var actor=require("departments:read");
        List<Object> args=new ArrayList<>(List.of(actor.tenantId()));
        String scope=allowed(actor,"departments:read","id",args);
        return repo.rows("SELECT * FROM sys_department WHERE tenant_id=?" + scope + " ORDER BY sort_order,id",args.toArray());
    }
    @Transactional public long saveDepartment(Long recordId, DepartmentSave request) {
        var actor=write("departments:write",true); long tenant=actor.tenantId();
        if (recordId != null) entity("sys_department",tenant,recordId);
        long parent=request.parentId() == null ? 0 : request.parentId();
        Set<Long> visited=new HashSet<>();
        if (recordId != null) visited.add(recordId);
        long cursor=parent;
        while (cursor != 0) {
            if (!visited.add(cursor)) throw new ApiException(400,"部门不能选择自己或下级作为上级");
            var department=entity("sys_department",tenant,cursor);
            if (id(department,"status") != 1) throw new ApiException(409,"上级部门已停用");
            cursor=id(department,"parentId");
        }
        if (recordId != null && request.status()==0) assertDepartmentUnused(tenant,recordId);
        if (recordId != null && !request.type().equals("STORE") && repo.count("SELECT COUNT(*) FROM biz_order WHERE tenant_id=? AND department_id=?",tenant,recordId)>0)
            throw new ApiException(409,"已有订单的门店不能变更为部门，请保留历史订单的门店关联");
        if (recordId == null) return repo.insert("INSERT INTO sys_department(tenant_id,parent_id,code,name,type,sort_order,status) VALUES(?,?,?,?,?,?,?)",tenant,parent,request.code().trim(),request.name().trim(),request.type(),request.sortOrder(),request.status());
        repo.update("UPDATE sys_department SET parent_id=?,code=?,name=?,type=?,sort_order=?,status=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",parent,request.code().trim(),request.name().trim(),request.type(),request.sortOrder(),request.status(),tenant,recordId);
        return recordId;
    }
    private void assertDepartmentUnused(long tenant, long department) {
        if (repo.count("SELECT COUNT(*) FROM sys_department WHERE tenant_id=? AND parent_id=?",tenant,department)>0
            || repo.count("SELECT COUNT(*) FROM sys_user_department WHERE tenant_id=? AND department_id=?",tenant,department)>0
            || repo.count("SELECT COUNT(*) FROM sys_department_room WHERE tenant_id=? AND department_id=?",tenant,department)>0
            || repo.count("SELECT COUNT(*) FROM sys_user_role WHERE tenant_id=? AND department_id=?",tenant,department)>0)
            throw new ApiException(409,"请先移除下级部门、房间、用户及角色关联，再停用或删除");
    }
    @Transactional public void deleteDepartment(long recordId) {
        var actor=write("departments:write",true);
        entity("sys_department",actor.tenantId(),recordId); assertDepartmentUnused(actor.tenantId(),recordId);
        if (repo.count("SELECT COUNT(*) FROM biz_order WHERE tenant_id=? AND department_id=?",actor.tenantId(),recordId)>0)
            throw new ApiException(409,"该门店已有订单，不能删除；可停用门店以保留历史记录");
        repo.update("DELETE FROM sys_department WHERE tenant_id=? AND id=?",actor.tenantId(),recordId);
    }

    public Page<Map<String,Object>> rooms(int page, int pageSize, String search, Long departmentId) {
        page(page,pageSize); var actor=require("rooms:read");
        List<Object> args=new ArrayList<>(List.of(actor.tenantId()));
        String where=" WHERE r.tenant_id=?" + allowed(actor,"rooms:read","dr.department_id",args);
        if (departmentId != null) { where+=" AND dr.department_id=?"; args.add(departmentId); }
        where+=" AND (LOWER(r.name) LIKE ? ESCAPE '!' OR LOWER(r.code) LIKE ? ESCAPE '!')";
        args.add(keyword(search)); args.add(keyword(search));
        String from=" FROM sys_room r JOIN sys_department_room dr ON dr.tenant_id=r.tenant_id AND dr.room_id=r.id JOIN sys_department d ON d.tenant_id=dr.tenant_id AND d.id=dr.department_id";
        long total=repo.count("SELECT COUNT(*)"+from+where,args.toArray());
        args.add(pageSize); args.add((page-1)*pageSize);
        return new Page<>(repo.rows("SELECT r.*,d.id department_id,d.name department_name"+from+where+" ORDER BY r.id DESC LIMIT ? OFFSET ?",args.toArray()),total,page,pageSize);
    }
    @Transactional public long saveRoom(Long recordId, RoomSave request) {
        var actor=write("rooms:write",false); long tenant=actor.tenantId();
        actor.requireDepartment("rooms:write",request.departmentId()); activeDepartment(tenant,request.departmentId());
        if (recordId != null) {
            entity("sys_room",tenant,recordId);
            var relation=repo.one("SELECT department_id FROM sys_department_room WHERE tenant_id=? AND room_id=?",tenant,recordId);
            if (relation == null) throw new ApiException(409,"房间归属数据异常，请联系管理员");
            actor.requireDepartment("rooms:write",id(relation,"departmentId"));
            repo.update("UPDATE sys_room SET code=?,name=?,capacity=?,status=?,remark=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",request.code().trim(),request.name().trim(),request.capacity(),request.status(),request.remark(),tenant,recordId);
            repo.update("UPDATE sys_department_room SET department_id=? WHERE tenant_id=? AND room_id=?",request.departmentId(),tenant,recordId);
            return recordId;
        }
        long room=repo.insert("INSERT INTO sys_room(tenant_id,code,name,capacity,status,remark) VALUES(?,?,?,?,?,?)",tenant,request.code().trim(),request.name().trim(),request.capacity(),request.status(),request.remark());
        repo.update("INSERT INTO sys_department_room(tenant_id,department_id,room_id) VALUES(?,?,?)",tenant,request.departmentId(),room);
        return room;
    }
    @Transactional public void deleteRoom(long recordId) {
        var actor=write("rooms:write",false); long tenant=actor.tenantId();
        entity("sys_room",tenant,recordId);
        var relation=repo.one("SELECT department_id FROM sys_department_room WHERE tenant_id=? AND room_id=?",tenant,recordId);
        if (relation == null) throw new ApiException(409,"房间归属数据异常");
        actor.requireDepartment("rooms:write",id(relation,"departmentId"));
        repo.update("DELETE FROM sys_department_room WHERE tenant_id=? AND room_id=?",tenant,recordId);
        repo.update("DELETE FROM sys_room WHERE tenant_id=? AND id=?",tenant,recordId);
    }

    public Page<Map<String,Object>> roles(int page, int pageSize, String search) {
        page(page,pageSize); var actor=require("roles:read"); actor.requireGlobal("roles:read");
        String where=" WHERE tenant_id=? AND (LOWER(name) LIKE ? ESCAPE '!' OR LOWER(code) LIKE ? ESCAPE '!')";
        long total=repo.count("SELECT COUNT(*) FROM sys_role"+where,actor.tenantId(),keyword(search),keyword(search));
        var rows=repo.rows("SELECT * FROM sys_role"+where+" ORDER BY builtin DESC,id LIMIT ? OFFSET ?",actor.tenantId(),keyword(search),keyword(search),pageSize,(page-1)*pageSize);
        var permissions=repo.rows("SELECT rp.role_id,p.code FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.tenant_id=?",actor.tenantId());
        rows.forEach(row -> row.put("permissionCodes",permissions.stream().filter(p -> id(p,"roleId")==id(row,"id")).map(p -> text(p,"code")).toList()));
        return new Page<>(rows,total,page,pageSize);
    }
    @Transactional public long saveRole(Long recordId, RoleSave request) {
        var actor=write("roles:write",true); long tenant=actor.tenantId();
        if (request.code().equals("ADMIN")) throw new ApiException(409,"企业管理员角色由平台维护，不可修改");
        if (recordId != null) {
            var old=entity("sys_role",tenant,recordId);
            if (text(old,"code").equals("ADMIN")) throw new ApiException(409,"企业管理员角色不可修改");
            if (id(old,"builtin")==1 && !request.code().equals(text(old,"code"))) throw new ApiException(400,"内置角色编码不可修改");
        }
        var catalog=repo.rows("SELECT id,code FROM sys_permission");
        Set<String> codes=new HashSet<>(request.permissionCodes());
        if (codes.size()!=request.permissionCodes().size()) throw new ApiException(400,"权限不能重复");
        for (String code : codes) if (code.endsWith(":write") && !codes.contains(code.replace(":write",":read")))
            throw new ApiException(400,"维护权限必须同时包含对应的查看权限");
        for (String code : codes) {
            if (catalog.stream().noneMatch(p -> text(p,"code").equals(code))) throw new ApiException(400,"包含不存在的权限");
            if (!actor.global(code)) throw new ApiException(403,"不能授予自己不具备的企业范围权限");
        }
        long role;
        if (recordId == null) role=repo.insert("INSERT INTO sys_role(tenant_id,code,name,status,description) VALUES(?,?,?,?,?)",tenant,request.code(),request.name().trim(),request.status(),request.description());
        else { role=recordId; repo.update("UPDATE sys_role SET code=?,name=?,status=?,description=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",request.code(),request.name().trim(),request.status(),request.description(),tenant,role); }
        repo.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=?",tenant,role);
        for (var permission : catalog) if (codes.contains(text(permission,"code"))) repo.update("INSERT INTO sys_role_permission(tenant_id,role_id,permission_id) VALUES(?,?,?)",tenant,role,id(permission,"id"));
        return role;
    }
    @Transactional public void deleteRole(long recordId) {
        var actor=write("roles:write",true); long tenant=actor.tenantId();
        var role=entity("sys_role",tenant,recordId);
        if (id(role,"builtin")==1) throw new ApiException(409,"内置身份不能删除");
        if (repo.count("SELECT COUNT(*) FROM sys_user_role WHERE tenant_id=? AND role_id=?",tenant,recordId)>0) throw new ApiException(409,"角色正在被用户使用，请先解除关联");
        repo.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=?",tenant,recordId);
        repo.update("DELETE FROM sys_role WHERE tenant_id=? AND id=?",tenant,recordId);
    }

    public Page<Map<String,Object>> users(int page, int pageSize, String search, Long departmentId, Integer status) {
        page(page,pageSize); var actor=require("users:read");
        if (status != null && status!=0 && status!=1) throw new ApiException(400,"用户状态不正确");
        // Otherwise a multi-store employee could reveal membership in an unauthorized store through filtering.
        if (departmentId != null) actor.requireDepartment("users:read",departmentId);
        List<Object> args=new ArrayList<>(List.of(actor.tenantId()));
        String where=" WHERE u.tenant_id=? AND u.deleted=0";
        if (!actor.global("users:read")) where+=" AND EXISTS(SELECT 1 FROM sys_user_department ud WHERE ud.tenant_id=u.tenant_id AND ud.user_id=u.id" + allowed(actor,"users:read","ud.department_id",args) + ")";
        if (departmentId != null) { where+=" AND EXISTS(SELECT 1 FROM sys_user_department ud WHERE ud.tenant_id=u.tenant_id AND ud.user_id=u.id AND ud.department_id=?)"; args.add(departmentId); }
        if (status != null) { where+=" AND u.status=?"; args.add(status); }
        where+=" AND (LOWER(u.username) LIKE ? ESCAPE '!' OR LOWER(u.nickname) LIKE ? ESCAPE '!' OR u.phone LIKE ? ESCAPE '!')";
        args.add(keyword(search)); args.add(keyword(search)); args.add(keyword(search));
        long total=repo.count("SELECT COUNT(*) FROM sys_user u"+where,args.toArray());
        args.add(pageSize); args.add((page-1)*pageSize);
        var rows=repo.rows("SELECT u.id,u.username,u.nickname,u.phone,u.email,u.status,u.create_time FROM sys_user u"+where+" ORDER BY u.id DESC LIMIT ? OFFSET ?",args.toArray());
        if (!rows.isEmpty()) {
            List<Object> relatedArgs=new ArrayList<>(List.of(actor.tenantId())); rows.forEach(row -> relatedArgs.add(id(row,"id")));
            String in=" IN ("+marks(rows.size())+")";
            var departments=repo.rows("SELECT ud.user_id,d.id,d.name FROM sys_user_department ud JOIN sys_department d ON d.tenant_id=ud.tenant_id AND d.id=ud.department_id WHERE ud.tenant_id=? AND ud.user_id"+in,relatedArgs.toArray());
            var grants=repo.rows("SELECT ur.user_id,ur.role_id,ur.department_id,r.name role_name,d.name department_name FROM sys_user_role ur JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id LEFT JOIN sys_department d ON d.tenant_id=ur.tenant_id AND d.id=ur.department_id WHERE ur.tenant_id=? AND ur.user_id"+in,relatedArgs.toArray());
            long ownerId=repo.count("SELECT user_id FROM sys_tenant_admin WHERE tenant_id=?",actor.tenantId());
            for (var row : rows) {
                long userId=id(row,"id");
                var userDepartments=departments.stream().filter(d -> id(d,"userId")==userId && actor.can("users:read",id(d,"id"))).toList();
                row.put("departmentIds",userDepartments.stream().map(d -> id(d,"id")).toList()); row.put("departments",userDepartments);
                row.put("roleGrants",grants.stream().filter(g -> id(g,"userId")==userId && (id(g,"departmentId")==0 || actor.can("users:read",id(g,"departmentId")))).toList());
                row.put("owner",userId==ownerId);
            }
        }
        return new Page<>(rows,total,page,pageSize);
    }
    @Transactional public long saveUser(Long recordId, UserSave request) {
        var actor=write("users:write",true); long tenant=actor.tenantId();
        boolean isOwner=recordId!=null && owner(tenant,recordId);
        if (recordId != null) {
            var old=entity("sys_user",tenant,recordId);
            if (!request.username().equals(text(old,"username"))) throw new ApiException(400,"账号创建后不可修改");
            if ((isOwner || recordId==actor.userId()) && request.status()==0) throw new ApiException(409,"不能停用企业管理员或当前登录账号");
            if (request.password()!=null && !request.password().isEmpty()) throw new ApiException(400,"修改密码请使用重置密码操作");
        } else if (request.username().equalsIgnoreCase("admin")) throw new ApiException(409,"admin 为企业负责人保留账号");
        Set<Long> departments=new LinkedHashSet<>(request.departmentIds());
        if (departments.size()!=request.departmentIds().size()) throw new ApiException(400,"部门不能重复");
        departments.forEach(d -> activeDepartment(tenant,d));
        Set<String> duplicates=new HashSet<>(); boolean ownerGrant=false;
        for (var grant : request.roleGrants()) {
            long department=grant.departmentId()==null ? 0 : grant.departmentId();
            if (!duplicates.add(grant.roleId()+":"+department)) throw new ApiException(400,"角色与部门授权不能重复");
            var role=entity("sys_role",tenant,grant.roleId());
            if (id(role,"status")!=1) throw new ApiException(409,"不能分配已停用的角色");
            if (department!=0 && !departments.contains(department)) throw new ApiException(400,"角色所在部门必须在用户所属部门中");
            if (text(role,"code").equals("ADMIN")) {
                if (!isOwner || department!=0) throw new ApiException(403,"企业管理员身份只能绑定本企业负责人");
                ownerGrant=true;
            }
            var permissions=repo.rows("SELECT p.code FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.tenant_id=? AND rp.role_id=?",tenant,grant.roleId());
            for (var permission : permissions) {
                String code=text(permission,"code");
                if (department!=0 && AccessService.COMPANY_PERMISSIONS.contains(code)) throw new ApiException(400,"包含企业管理权限的角色必须使用企业范围授权");
                if (!actor.can(code,department)) throw new ApiException(403,"不能授予超过自身范围的权限");
            }
        }
        if (isOwner && !ownerGrant) throw new ApiException(409,"不能移除企业负责人的管理员身份");
        long user;
        if (recordId==null) user=repo.insert("INSERT INTO sys_user(tenant_id,username,password,nickname,phone,email,status) VALUES(?,?,?,?,?,?,?)",tenant,request.username(),passwords.hash(request.password()),request.nickname().trim(),request.phone(),request.email(),request.status());
        else {
            user=recordId;
            repo.update("UPDATE sys_user SET nickname=?,phone=?,email=?,auth_version=auth_version+CASE WHEN status<>? THEN 1 ELSE 0 END,status=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",request.nickname().trim(),request.phone(),request.email(),request.status(),request.status(),tenant,user);
        }
        repo.update("DELETE FROM sys_user_role WHERE tenant_id=? AND user_id=?",tenant,user);
        repo.update("DELETE FROM sys_user_department WHERE tenant_id=? AND user_id=?",tenant,user);
        for (long d : departments) repo.update("INSERT INTO sys_user_department(tenant_id,user_id,department_id) VALUES(?,?,?)",tenant,user,d);
        for (var grant : request.roleGrants()) repo.update("INSERT INTO sys_user_role(tenant_id,user_id,role_id,department_id) VALUES(?,?,?,?)",tenant,user,grant.roleId(),grant.departmentId()==null ? 0 : grant.departmentId());
        return user;
    }
    @Transactional public void deleteUser(long recordId) {
        var actor=write("users:write",true); long tenant=actor.tenantId(); entity("sys_user",tenant,recordId);
        if (owner(tenant,recordId) || recordId==actor.userId()) throw new ApiException(409,"不能删除企业管理员或当前登录账号");
        repo.update("DELETE FROM sys_user_role WHERE tenant_id=? AND user_id=?",tenant,recordId);
        repo.update("DELETE FROM sys_user_department WHERE tenant_id=? AND user_id=?",tenant,recordId);
        repo.update("UPDATE sys_user SET deleted=1,status=0,auth_version=auth_version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",tenant,recordId);
    }
    @Transactional public void resetPassword(long recordId, PasswordReset request) {
        var actor=write("users:write",true); entity("sys_user",actor.tenantId(),recordId);
        if (recordId==actor.userId()) throw new ApiException(400,"请通过顶部账号菜单修改自己的密码");
        if (owner(actor.tenantId(),recordId)) throw new ApiException(403,"不能重置企业负责人的密码");
        repo.update("UPDATE sys_user SET password=?,auth_version=auth_version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",passwords.hash(request.password()),actor.tenantId(),recordId);
    }

    public Map<String,Object> options() {
        var actor=access.currentTenant();
        var departments=repo.rows("SELECT id,parent_id,code,name,type,sort_order,status FROM sys_department WHERE tenant_id=? ORDER BY sort_order,id",actor.tenantId());
        departments=departments.stream().filter(d -> actor.can("departments:read",id(d,"id")) || actor.can("rooms:read",id(d,"id")) || actor.can("rooms:write",id(d,"id")) || actor.can("users:read",id(d,"id")) || actor.global("users:write")).toList();
        List<Map<String,Object>> roles=(actor.global("roles:read") || actor.global("users:write")) ? repo.rows("SELECT id,code,name,builtin,status,description FROM sys_role WHERE tenant_id=? ORDER BY id",actor.tenantId()) : List.of();
        if (!roles.isEmpty()) {
            var grants=repo.rows("SELECT rp.role_id,p.code FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.tenant_id=?",actor.tenantId());
            roles.forEach(role -> role.put("permissionCodes",grants.stream().filter(p -> id(p,"roleId")==id(role,"id")).map(p -> text(p,"code")).toList()));
        }
        List<Map<String,Object>> permissions=actor.global("roles:read") ? repo.rows("SELECT code,name,module FROM sys_permission ORDER BY id") : List.of();
        var roomDepartmentIds=departments.stream().filter(d -> id(d,"status")==1 && actor.can("rooms:write",id(d,"id"))).map(d -> id(d,"id")).toList();
        return Map.of("departments",departments,"roles",roles,"permissions",permissions,"companyPermissions",AccessService.COMPANY_PERMISSIONS,"roomDepartmentIds",roomDepartmentIds);
    }
}
