-- V5: item catalog, inventory balances/changes, and commission schemes.
-- All business rows are tenant-scoped and intentionally use logical links.
CREATE TABLE IF NOT EXISTS biz_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(150) NOT NULL,
    category VARCHAR(100),
    price DECIMAL(14,2) NOT NULL DEFAULT 0,
    duration_minutes INT,
    unit VARCHAR(30),
    spec VARCHAR(80),
    description VARCHAR(1000),
    status TINYINT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_biz_item_code (tenant_id,kind,code),
    INDEX idx_biz_item_list (tenant_id,kind,status,id),
    CHECK (kind IN ('PROJECT','PRODUCT','CARD')),
    CHECK (price >= 0)
);

CREATE TABLE IF NOT EXISTS biz_inventory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    quantity DECIMAL(14,3) NOT NULL DEFAULT 0,
    cost_price DECIMAL(14,2) NOT NULL DEFAULT 0,
    warning_value DECIMAL(14,3) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inventory_item_department (tenant_id,department_id,item_id),
    INDEX idx_inventory_tenant_quantity (tenant_id,department_id,quantity),
    CHECK (quantity >= 0),
    CHECK (cost_price >= 0),
    CHECK (warning_value >= 0)
);

CREATE TABLE IF NOT EXISTS biz_inventory_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    inventory_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    quantity DECIMAL(14,3) NOT NULL,
    unit_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    reason VARCHAR(300),
    reference_no VARCHAR(80),
    actor_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_inventory_change_list (tenant_id,department_id,create_time,id),
    CHECK (change_type IN ('IN','OUT','ADJUST')),
    CHECK (quantity > 0)
);

CREATE TABLE IF NOT EXISTS biz_commission_scheme (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    name VARCHAR(120) NOT NULL,
    basis VARCHAR(20) NOT NULL DEFAULT 'PERCENT',
    rate DECIMAL(8,4) NOT NULL DEFAULT 0,
    fixed_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    description VARCHAR(500),
    config_json TEXT,
    status TINYINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_commission_scheme_name (tenant_id,kind,name),
    INDEX idx_commission_scheme_list (tenant_id,kind,status,id),
    CHECK (kind IN ('PROJECT','PRODUCT','CARD','STEP')),
    CHECK (basis IN ('PERCENT','AMOUNT')),
    CHECK (rate >= 0),
    CHECK (fixed_amount >= 0)
);

CREATE TABLE IF NOT EXISTS biz_commission_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    scheme_id BIGINT NOT NULL,
    item_id BIGINT,
    min_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    max_amount DECIMAL(14,2),
    basis VARCHAR(20) NOT NULL,
    rate DECIMAL(8,4) NOT NULL DEFAULT 0,
    fixed_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    INDEX idx_commission_rule_scheme (tenant_id,scheme_id,sort_order,id),
    CHECK (basis IN ('PERCENT','AMOUNT')),
    CHECK (min_amount >= 0),
    CHECK (rate >= 0),
    CHECK (fixed_amount >= 0)
);
