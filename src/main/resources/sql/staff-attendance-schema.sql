CREATE TABLE IF NOT EXISTS biz_staff_attendance_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(20) NOT NULL,
    attendance_type VARCHAR(20) NOT NULL,
    all_stores TINYINT NOT NULL,
    all_employees TINYINT NOT NULL,
    rule_json LONGTEXT NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    updater_user_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_staff_attendance_rule_tenant (tenant_id,deleted,id)
);

CREATE TABLE IF NOT EXISTS biz_staff_attendance_rule_store (
    tenant_id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id,rule_id,department_id),
    INDEX idx_staff_attendance_rule_store (tenant_id,department_id,rule_id)
);

CREATE TABLE IF NOT EXISTS biz_staff_attendance_rule_user (
    tenant_id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id,rule_id,user_id),
    INDEX idx_staff_attendance_rule_user (tenant_id,user_id,rule_id)
);
