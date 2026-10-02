CREATE TABLE IF NOT EXISTS biz_appointment_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    business_start TIME NOT NULL DEFAULT '09:00:00',
    business_end TIME NOT NULL DEFAULT '19:30:00',
    staff_ids VARCHAR(2000) NOT NULL DEFAULT '',
    public_booking_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    public_image_data LONGTEXT,
    public_slots VARCHAR(2000) NOT NULL DEFAULT '09:00-19:30',
    public_weekdays VARCHAR(30) NOT NULL DEFAULT '1,2,3,4,5,6,7',
    closed_dates VARCHAR(2000) NOT NULL DEFAULT '',
    advance_minutes INT NOT NULL DEFAULT 120,
    interval_minutes INT NOT NULL DEFAULT 20,
    max_advance_days INT NOT NULL DEFAULT 30,
    prevent_conflicts BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id,department_id)
);

ALTER TABLE biz_appointment ADD COLUMN party_size INT NOT NULL DEFAULT 1;
ALTER TABLE biz_appointment ADD COLUMN needs_tea BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE biz_appointment ADD COLUMN needs_air_conditioner BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE biz_appointment ADD COLUMN brings_pet BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE biz_appointment ADD COLUMN brings_child BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE biz_appointment ADD COLUMN needs_bath BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE biz_appointment ADD COLUMN booking_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE biz_appointment ADD COLUMN series_id VARCHAR(36);
CREATE INDEX idx_appointment_series ON biz_appointment(tenant_id,series_id);
