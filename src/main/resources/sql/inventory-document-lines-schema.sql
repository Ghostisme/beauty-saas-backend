-- V11: product movement document lines. Header-only documents remain supported
-- for existing stocktaking and transfer workflows.
ALTER TABLE biz_inventory_batch ADD COLUMN quantity DECIMAL(14,3) NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS biz_inventory_doc_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    quantity DECIMAL(14,3) NOT NULL,
    unit_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    batch_name VARCHAR(120),
    production_date DATE,
    expiry_date DATE,
    remark VARCHAR(300),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_inventory_doc_line_document (tenant_id, document_id, id),
    INDEX idx_inventory_doc_line_item (tenant_id, department_id, item_id, create_time),
    CHECK (quantity > 0),
    CHECK (unit_cost >= 0)
);
