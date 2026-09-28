-- V6: tenant-scoped appointment calendar records.
-- Appointment rows intentionally keep staff / room / service snapshots so historical
-- calendar entries remain readable when IAM or catalog records change.
CREATE TABLE IF NOT EXISTS biz_appointment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    appointment_date DATE NOT NULL,
    start_time TIME NOT NULL,
    duration_minutes INT NOT NULL,
    customer_name VARCHAR(80) NOT NULL,
    phone VARCHAR(30),
    service_name VARCHAR(150) NOT NULL,
    staff_name VARCHAR(80),
    room_name VARCHAR(80),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    color VARCHAR(20) NOT NULL DEFAULT '#1677ff',
    note VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_appointment_calendar (tenant_id,department_id,appointment_date,start_time,id),
    INDEX idx_appointment_status (tenant_id,appointment_date,status),
    CHECK (duration_minutes > 0 AND duration_minutes <= 1440),
    CHECK (status IN ('PENDING','CONFIRMED','ARRIVED','DONE','CANCELLED','TEMP_BLOCK'))
);
