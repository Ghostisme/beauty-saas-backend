package com.beauty.saas.staff;

import com.beauty.saas.common.ApiException;
import com.beauty.saas.dto.StaffPositionSave;
import com.beauty.saas.iam.IamRepository;
import com.beauty.saas.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.beauty.saas.iam.IamRepository.id;

@Service
@RequiredArgsConstructor
public class StaffPositionService {
    private final IamRepository repo;
    private final AccessService access;

    public List<Map<String,Object>> list() {
        var actor = access.currentTenant();
        actor.requireGlobal("roles:read");
        return repo.rows("SELECT id,code,name,remark,status,create_time FROM biz_staff_position WHERE tenant_id=? ORDER BY id", actor.tenantId());
    }

    @Transactional public long save(Long recordId, StaffPositionSave input) {
        var actor = access.currentTenant();
        actor.requireGlobal("roles:write");
        long tenant = actor.tenantId();
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? AND status=1 FOR UPDATE", tenant) == null)
            throw new ApiException(409, "企业已停用");
        access.reload(actor).requireGlobal("roles:write");
        if (recordId != null && repo.one("SELECT id FROM biz_staff_position WHERE tenant_id=? AND id=?", tenant, recordId) == null)
            throw new ApiException(404, "职位不存在");
        String name = input.name().trim();
        if (name.isEmpty()) throw new ApiException(400, "请输入职位名称");
        String code = input.code() == null || input.code().isBlank() ? null : input.code().trim();
        if (repo.count("SELECT COUNT(*) FROM biz_staff_position WHERE tenant_id=? AND name=? AND id<>?", tenant, name, recordId == null ? 0 : recordId) > 0)
            throw new ApiException(409, "职位名称已存在");
        if (code != null && repo.count("SELECT COUNT(*) FROM biz_staff_position WHERE tenant_id=? AND code=? AND id<>?", tenant, code, recordId == null ? 0 : recordId) > 0)
            throw new ApiException(409, "职位编号已存在");
        if (recordId != null && input.status() == 0 && inUse(tenant, recordId))
            throw new ApiException(409, "职位已关联员工或 SOP 规则，不能停用");
        if (recordId == null) return repo.insert("INSERT INTO biz_staff_position(tenant_id,code,name,remark,status) VALUES(?,?,?,?,?)", tenant, code, name, input.remark(), input.status());
        repo.update("UPDATE biz_staff_position SET code=?,name=?,remark=?,status=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?", code, name, input.remark(), input.status(), tenant, recordId);
        return recordId;
    }

    @Transactional public void delete(long recordId) {
        var actor = access.currentTenant();
        actor.requireGlobal("roles:write");
        long tenant = actor.tenantId();
        if (repo.one("SELECT id FROM sys_tenant WHERE id=? AND status=1 FOR UPDATE", tenant) == null)
            throw new ApiException(409, "企业已停用");
        access.reload(actor).requireGlobal("roles:write");
        var row = repo.one("SELECT id FROM biz_staff_position WHERE tenant_id=? AND id=?", tenant, recordId);
        if (row == null) throw new ApiException(404, "职位不存在");
        if (inUse(tenant, id(row,"id"))) throw new ApiException(409, "职位已关联员工或 SOP 规则，不能删除");
        repo.update("DELETE FROM biz_staff_position WHERE tenant_id=? AND id=?", tenant, recordId);
    }

    private boolean inUse(long tenant, long positionId) {
        return repo.count("SELECT COUNT(*) FROM sys_user WHERE tenant_id=? AND position_id=? AND deleted=0", tenant, positionId) > 0
            || repo.count("SELECT COUNT(*) FROM biz_staff_sop_rule_position rp JOIN biz_staff_sop_rule r ON r.tenant_id=rp.tenant_id AND r.id=rp.rule_id WHERE rp.tenant_id=? AND rp.position_id=? AND r.deleted=0",tenant,positionId)>0;
    }
}
