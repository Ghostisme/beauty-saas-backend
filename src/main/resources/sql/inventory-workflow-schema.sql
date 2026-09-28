-- V7: inventory batches, stocktaking and transfer workflow records.
CREATE TABLE IF NOT EXISTS biz_inventory_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    batch_name VARCHAR(120) NOT NULL,
    production_date DATE,
    expiry_date DATE,
    remark VARCHAR(300),
    status TINYINT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_inventory_batch_list (tenant_id,department_id,item_id,status,create_time)
);

CREATE TABLE IF NOT EXISTS biz_inventory_doc (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    doc_type VARCHAR(30) NOT NULL,
    document_no VARCHAR(80) NOT NULL,
    source_department_id BIGINT,
    target_department_id BIGINT,
    document_date DATE NOT NULL,
    operator_name VARCHAR(80),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    remark VARCHAR(300),
    creator_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inventory_doc_no (tenant_id,document_no),
    INDEX idx_inventory_doc_list (tenant_id,doc_type,document_date,status,create_time),
    CHECK (doc_type IN ('LIQUIDATION','TRANSFER_IN','TRANSFER_OUT','COST_ADJUST')),
    CHECK (status IN ('DRAFT','PENDING','CONFIRMED','CANCELLED'))
);
