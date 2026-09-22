-- V3: order snapshots and logical associations. Intentionally no foreign keys.
CREATE TABLE IF NOT EXISTS biz_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    consumption_type VARCHAR(40) NOT NULL,
    customer_id BIGINT,
    customer_name VARCHAR(100) NOT NULL,
    customer_phone VARCHAR(20),
    customer_code VARCHAR(50),
    store_name VARCHAR(100),
    order_time DATETIME NOT NULL,
    total_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    paid_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    remark VARCHAR(1000),
    performance_version BIGINT NOT NULL DEFAULT 0,
    reviewed_performance_version BIGINT,
    reviewed_at DATETIME,
    reviewed_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_number (tenant_id,order_no),
    INDEX idx_order_tenant_status_time (tenant_id,status,order_time,id),
    INDEX idx_order_department_time (tenant_id,department_id,order_time),
    INDEX idx_order_platform_time (status,order_time,id)
);
CREATE TABLE IF NOT EXISTS biz_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    source_id BIGINT,
    consumption_type VARCHAR(40) NOT NULL,
    name VARCHAR(150) NOT NULL,
    quantity DECIMAL(10,2) NOT NULL DEFAULT 1,
    unit_price DECIMAL(14,2) NOT NULL DEFAULT 0,
    amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    INDEX idx_order_item (tenant_id,order_id,id)
);
CREATE TABLE IF NOT EXISTS biz_order_staff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    user_id BIGINT,
    staff_name VARCHAR(100) NOT NULL,
    staff_phone VARCHAR(20),
    staff_code VARCHAR(50),
    INDEX idx_order_staff (tenant_id,order_id,id)
);
CREATE TABLE IF NOT EXISTS biz_order_payment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    method VARCHAR(40) NOT NULL,
    category VARCHAR(40) NOT NULL,
    amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    INDEX idx_order_payment (tenant_id,order_id,method,category)
);
CREATE TABLE IF NOT EXISTS biz_order_verification_setting (
    tenant_id BIGINT PRIMARY KEY,
    enabled TINYINT NOT NULL DEFAULT 1,
    recheck_after_performance_change TINYINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by BIGINT,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS biz_order_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    order_id BIGINT,
    actor_id BIGINT NOT NULL,
    action VARCHAR(60) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order_audit (tenant_id,order_id,create_time)
);
