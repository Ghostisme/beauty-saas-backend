-- V4: enterprise SMS configuration, history and billing. Logical links only; no foreign keys.
CREATE TABLE IF NOT EXISTS biz_sms_setting (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    template_code VARCHAR(50) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    config_json TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by BIGINT NOT NULL,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sms_setting (tenant_id,template_code)
);
CREATE TABLE IF NOT EXISTS biz_sms_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    template_code VARCHAR(50) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    billed_units INT NOT NULL DEFAULT 0,
    failure_reason VARCHAR(500),
    provider_message_id VARCHAR(150),
    business_key VARCHAR(150) NOT NULL,
    send_time DATETIME NOT NULL,
    delivered_time DATETIME,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sms_business_message (tenant_id,business_key),
    INDEX idx_sms_record_tenant_time (tenant_id,send_time,id),
    INDEX idx_sms_record_phone_time (tenant_id,phone,send_time),
    INDEX idx_sms_record_platform_time (send_time,id),
    CHECK (billed_units>=0)
);
CREATE TABLE IF NOT EXISTS biz_sms_account (
    tenant_id BIGINT PRIMARY KEY,
    balance BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (balance>=0)
);
CREATE TABLE IF NOT EXISTS biz_sms_package (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(100) NOT NULL,
    units INT NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    active TINYINT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,
    UNIQUE KEY uk_sms_package_code (code),
    CHECK (units>0),
    CHECK (price>0)
);
CREATE TABLE IF NOT EXISTS biz_sms_recharge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    package_id BIGINT NOT NULL,
    package_version BIGINT NOT NULL,
    package_name VARCHAR(100) NOT NULL,
    units INT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(50) NOT NULL,
    created_by BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_time DATETIME,
    cancelled_time DATETIME,
    UNIQUE KEY uk_sms_recharge_order (tenant_id,order_no),
    UNIQUE KEY uk_sms_recharge_request (tenant_id,idempotency_key),
    INDEX idx_sms_recharge_tenant_time (tenant_id,create_time,id),
    CHECK (units>0),
    CHECK (amount>0)
);
CREATE TABLE IF NOT EXISTS biz_sms_credit_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    reference_type VARCHAR(30) NOT NULL,
    reference_no VARCHAR(150) NOT NULL,
    delta_units BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sms_credit_reference (tenant_id,reference_type,reference_no),
    INDEX idx_sms_credit_tenant_time (tenant_id,create_time,id),
    CHECK (balance_after>=0)
);
CREATE TABLE IF NOT EXISTS biz_sms_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT,
    actor_id BIGINT NOT NULL,
    action VARCHAR(50) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sms_audit (tenant_id,create_time,id)
);
-- Initial catalog comes from the supplied reference. These are not provider quotes or paid orders.
INSERT INTO biz_sms_package(code,name,units,price,sort_order) VALUES
('SMS_1000','1000条短信包',1000,68.00,1),
('SMS_3000','3000条短信包',3000,180.00,2),
('SMS_6000','6000条短信包',6000,360.00,3),
('SMS_10000','10000条短信包',10000,600.00,4),
('SMS_50000','50000条短信包',50000,2888.00,5);
