-- V12: tenant-wide inventory behavior and warning settings.
CREATE TABLE IF NOT EXISTS biz_inventory_setting (
    tenant_id BIGINT PRIMARY KEY,
    prevent_order_on_shortage TINYINT NOT NULL DEFAULT 0,
    transfer_auto_confirm_enabled TINYINT NOT NULL DEFAULT 0,
    transfer_auto_confirm_days INT NOT NULL DEFAULT 0,
    stock_alert_enabled TINYINT NOT NULL DEFAULT 0,
    stock_alert_value DECIMAL(14,3) NOT NULL DEFAULT 0,
    expiry_alert_enabled TINYINT NOT NULL DEFAULT 0,
    expiry_alert_months INT NOT NULL DEFAULT 6,
    sales_deduct_inventory TINYINT NOT NULL DEFAULT 1,
    delete_product_sync_inventory TINYINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by BIGINT,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (transfer_auto_confirm_days >= 0),
    CHECK (stock_alert_value >= 0),
    CHECK (expiry_alert_months > 0)
);
