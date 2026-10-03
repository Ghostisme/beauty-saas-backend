CREATE TABLE IF NOT EXISTS biz_staff_sop_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    all_positions TINYINT NOT NULL,
    rule_json LONGTEXT NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    updater_user_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_staff_sop_rule_tenant (tenant_id,deleted,id)
);

CREATE TABLE IF NOT EXISTS biz_staff_sop_rule_position (
    tenant_id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    position_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id,rule_id,position_id),
    INDEX idx_staff_sop_position (tenant_id,position_id,rule_id)
);

CREATE TABLE IF NOT EXISTS biz_staff_sop_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    rule_id BIGINT NOT NULL,
    leaf_key VARCHAR(40) NOT NULL,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_staff_sop_check (tenant_id,department_id,user_id,work_date,rule_id,leaf_key),
    INDEX idx_staff_sop_check_month (tenant_id,department_id,work_date)
);
