-- V14: cost-accounting adjustment lines.  Cost adjustments change the
-- weighted-average unit cost without changing the on-hand quantity, so they
-- are kept separately from quantity-changing inventory ledger rows.
CREATE TABLE IF NOT EXISTS biz_inventory_cost_adjustment_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    quantity DECIMAL(14,3) NOT NULL DEFAULT 0,
    previous_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    unit_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    remark VARCHAR(300),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_inventory_cost_adjustment_line_document (tenant_id,document_id,id),
    INDEX idx_inventory_cost_adjustment_line_item (tenant_id,department_id,item_id,create_time),
    CHECK (quantity >= 0),
    CHECK (previous_cost >= 0),
    CHECK (unit_cost >= 0)
);
