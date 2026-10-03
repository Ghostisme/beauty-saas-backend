CREATE TABLE IF NOT EXISTS biz_staff_position (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    code VARCHAR(50),
    name VARCHAR(100) NOT NULL,
    remark VARCHAR(500),
    status TINYINT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_staff_position_name (tenant_id, name),
    UNIQUE KEY uk_staff_position_code (tenant_id, code)
);
