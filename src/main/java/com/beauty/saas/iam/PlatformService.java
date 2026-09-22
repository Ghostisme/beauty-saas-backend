package com.beauty.saas.iam;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.IamRequests.*;
import com.beauty.saas.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.beauty.saas.iam.IamRepository.*;

/** Platform operations are guarded by the platform identity association, never by an enterprise role name. */
@Service
@RequiredArgsConstructor
public class PlatformService {
    private final IamRepository repo;
    private final AccessService access;
    private final IdentityService identity;
    private final Passwords passwords;
    private AccountPrincipal platform() { var actor=access.current(); actor.requirePlatform(); return actor; }
    private AccountPrincipal lock(long tenant) {
        var actor=platform();
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? FOR UPDATE",tenant)==null) throw new ApiException(404,"企业不存在");
        access.reload(actor).requirePlatform(); return actor;
    }
    private void audit(AccountPrincipal actor,long tenant,String action,String detail) {
        repo.update("INSERT INTO sys_platform_audit(platform_user_id,tenant_id,action,detail) VALUES(?,?,?,?)",actor.userId(),tenant,action,detail);
    }
    private void page(int page,int size) { if (page<1 || page>100000 || size<1 || size>100) throw new ApiException(400,"分页参数不正确，每页最多 100 条"); }
    private String search(String value) {
        String input=Objects.toString(value,"").trim().toLowerCase(Locale.ROOT);
        if (input.length()>100) throw new ApiException(400,"搜索内容不能超过 100 字");
        return "%"+input.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    }
    private static final String TENANTS=" FROM sys_tenant t LEFT JOIN sys_tenant_admin a ON a.tenant_id=t.id LEFT JOIN sys_user u ON u.id=a.user_id AND u.tenant_id=t.id AND u.deleted=0";
    private static final String TENANT_COLUMNS="t.id,t.code,t.name,t.status,t.create_time,u.id admin_user_id,u.username admin_username,u.nickname admin_name,u.phone admin_phone,(SELECT COUNT(*) FROM sys_user x WHERE x.tenant_id=t.id AND x.deleted=0) user_count,(SELECT COUNT(*) FROM sys_department d WHERE d.tenant_id=t.id) department_count,(SELECT COUNT(*) FROM sys_room r WHERE r.tenant_id=t.id) room_count";
    public Map<String,Object> summary() {
        platform();
        return Map.of("tenants",repo.count("SELECT COUNT(*) FROM sys_tenant"),"users",repo.count("SELECT COUNT(*) FROM sys_user WHERE tenant_id>0 AND deleted=0"),
            "departments",repo.count("SELECT COUNT(*) FROM sys_department"),"rooms",repo.count("SELECT COUNT(*) FROM sys_room"));
    }
    public Page<Map<String,Object>> tenants(int page,int size,String keyword,Integer status) {
        platform(); page(page,size);
        List<Object> args=new ArrayList<>(List.of(search(keyword),search(keyword)));
        String where=" WHERE (LOWER(t.name) LIKE ? ESCAPE '!' OR LOWER(t.code) LIKE ? ESCAPE '!')";
        if (status!=null) { if (status!=0 && status!=1) throw new ApiException(400,"企业状态不正确"); where+=" AND t.status=?"; args.add(status); }
        long total=repo.count("SELECT COUNT(*)"+TENANTS+where,args.toArray());
        args.add(size); args.add((page-1)*size);
        return new Page<>(repo.rows("SELECT "+TENANT_COLUMNS+TENANTS+where+" ORDER BY t.id DESC LIMIT ? OFFSET ?",args.toArray()),total,page,size);
    }
    public Map<String,Object> tenant(long tenant) {
        platform(); var row=repo.one("SELECT "+TENANT_COLUMNS+TENANTS+" WHERE t.id=?",tenant);
        if (row==null) throw new ApiException(404,"企业不存在"); return row;
    }
    @Transactional public void save(long tenant,EnterpriseSave input) {
        var actor=lock(tenant);
        var old=repo.one("SELECT status FROM sys_tenant WHERE id=?",tenant);
        if (id(old,"status")!=input.status()) repo.update("UPDATE sys_user SET auth_version=auth_version+1 WHERE tenant_id=?",tenant);
        repo.update("UPDATE sys_tenant SET name=?,status=?,update_time=CURRENT_TIMESTAMP WHERE id=?",input.name().trim(),input.status(),tenant);
        audit(actor,tenant,"UPDATE_ENTERPRISE","更新企业名称/状态："+input.name().trim()+"，状态 "+input.status());
    }
    @Transactional public long createAdmin(long tenant,EnterpriseAdminCreate input) {
        var actor=lock(tenant);
        long user=identity.createEnterpriseAdmin(tenant,input.username(),input.nickname(),input.password(),input.phone());
        audit(actor,tenant,"CREATE_ENTERPRISE_ADMIN","开通企业管理员 "+input.username()); return user;
    }
    @Transactional public void resetAdmin(long tenant,PasswordReset input) {
        var actor=lock(tenant);
        var owner=repo.one("SELECT u.id FROM sys_tenant_admin a JOIN sys_user u ON u.tenant_id=a.tenant_id AND u.id=a.user_id AND u.deleted=0 WHERE a.tenant_id=?",tenant);
        if (owner==null) throw new ApiException(409,"此企业尚未开通管理员");
        repo.update("UPDATE sys_user SET password=?,auth_version=auth_version+1,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",passwords.hash(input.password()),tenant,id(owner,"id"));
        audit(actor,tenant,"RESET_ENTERPRISE_ADMIN_PASSWORD","重置企业管理员密码，旧会话已撤销");
    }
    public Page<Map<String,Object>> data(String kind,int page,int size,String keyword,Long tenantId) {
        platform(); page(page,size);
        if (tenantId!=null && (tenantId<=0 || repo.count("SELECT COUNT(*) FROM sys_tenant WHERE id=?",tenantId)==0)) throw new ApiException(404,"企业不存在");
        String columns,from,searchColumns;
        switch (kind) {
            case "users" -> {
                columns="x.id,x.tenant_id,x.username,x.nickname,x.phone,x.email,x.status,x.create_time,CASE WHEN EXISTS(SELECT 1 FROM sys_tenant_admin a WHERE a.tenant_id=x.tenant_id AND a.user_id=x.id) THEN 1 ELSE 0 END owner";
                from=" FROM sys_user x JOIN sys_tenant t ON t.id=x.tenant_id";
                searchColumns="LOWER(x.username) LIKE ? ESCAPE '!' OR LOWER(x.nickname) LIKE ? ESCAPE '!'";
            }
            case "departments" -> {
                columns="x.*,p.name parent_name"; from=" FROM sys_department x JOIN sys_tenant t ON t.id=x.tenant_id LEFT JOIN sys_department p ON p.id=x.parent_id AND p.tenant_id=x.tenant_id";
                searchColumns="LOWER(x.name) LIKE ? ESCAPE '!' OR LOWER(x.code) LIKE ? ESCAPE '!'";
            }
            case "rooms" -> {
                columns="x.*,d.id department_id,d.name department_name";
                from=" FROM sys_room x JOIN sys_tenant t ON t.id=x.tenant_id LEFT JOIN sys_department_room dr ON dr.tenant_id=x.tenant_id AND dr.room_id=x.id LEFT JOIN sys_department d ON d.tenant_id=dr.tenant_id AND d.id=dr.department_id";
                searchColumns="LOWER(x.name) LIKE ? ESCAPE '!' OR LOWER(x.code) LIKE ? ESCAPE '!'";
            }
            case "roles" -> { columns="x.*"; from=" FROM sys_role x JOIN sys_tenant t ON t.id=x.tenant_id"; searchColumns="LOWER(x.name) LIKE ? ESCAPE '!' OR LOWER(x.code) LIKE ? ESCAPE '!'"; }
            default -> throw new ApiException(404,"不支持的数据类型");
        }
        List<Object> args=new ArrayList<>(List.of(search(keyword),search(keyword)));
        String where=" WHERE ("+searchColumns+")"+(kind.equals("users")?" AND x.deleted=0":"");
        if (tenantId!=null) { where+=" AND x.tenant_id=?"; args.add(tenantId); }
        long total=repo.count("SELECT COUNT(*)"+from+where,args.toArray());
        args.add(size); args.add((page-1)*size);
        var rows=repo.rows("SELECT "+columns+",t.code tenant_code,t.name tenant_name,t.status tenant_status"+from+where+" ORDER BY x.id DESC LIMIT ? OFFSET ?",args.toArray());
        // The result page is bounded at 100. Association queries use only IDs from this authorized page.
        if (!rows.isEmpty() && kind.equals("users")) {
            String marks=String.join(",",Collections.nCopies(rows.size(),"?"));
            Object[] ids=rows.stream().map(r -> id(r,"id")).toArray();
            var departments=repo.rows("SELECT ud.user_id,d.id,d.name FROM sys_user_department ud JOIN sys_department d ON d.id=ud.department_id AND d.tenant_id=ud.tenant_id WHERE ud.user_id IN ("+marks+")",ids);
            var grants=repo.rows("SELECT ur.user_id,r.name role_name,d.name department_name FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id AND r.tenant_id=ur.tenant_id LEFT JOIN sys_department d ON d.id=ur.department_id AND d.tenant_id=ur.tenant_id WHERE ur.user_id IN ("+marks+")",ids);
            for (var row:rows) {
                long user=id(row,"id");
                row.put("owner",id(row,"owner")==1);
                row.put("departmentNames",departments.stream().filter(d -> id(d,"userId")==user).map(d -> text(d,"name")).toList());
                row.put("roleNames",grants.stream().filter(g -> id(g,"userId")==user).map(g -> text(g,"roleName")+" · "+(text(g,"departmentName").isEmpty()?"企业范围":text(g,"departmentName"))).toList());
            }
        }
        if (!rows.isEmpty() && kind.equals("roles")) {
            String marks=String.join(",",Collections.nCopies(rows.size(),"?"));
            var permissions=repo.rows("SELECT rp.role_id,p.code FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.role_id IN ("+marks+")",rows.stream().map(r -> id(r,"id")).toArray());
            rows.forEach(r -> r.put("permissionCodes",permissions.stream().filter(p -> id(p,"roleId")==id(r,"id")).map(p -> text(p,"code")).toList()));
        }
        return new Page<>(rows,total,page,size);
    }
}
