-- V13: stocktaking line snapshots with zero-safe actual quantities.
CREATE TABLE IF NOT EXISTS biz_inventory_liquidation_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    book_quantity DECIMAL(14,3) NOT NULL DEFAULT 0,
    actual_quantity DECIMAL(14,3) NOT NULL DEFAULT 0,
    remark VARCHAR(300),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_inventory_liquidation_line_document (tenant_id, document_id, id),
    INDEX idx_inventory_liquidation_line_item (tenant_id, department_id, item_id, create_time),
    CHECK (book_quantity >= 0),
    CHECK (actual_quantity >= 0)
);
