CREATE TABLE IF NOT EXISTS biz_staff_shift (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    color VARCHAR(7) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_staff_shift_name (tenant_id,name)
);

CREATE TABLE IF NOT EXISTS biz_staff_shift_store (
    tenant_id BIGINT NOT NULL,
    shift_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id,shift_id,department_id),
    INDEX idx_staff_shift_store (tenant_id,department_id,shift_id)
);

CREATE TABLE IF NOT EXISTS biz_staff_shift_period (
    tenant_id BIGINT NOT NULL,
    shift_id BIGINT NOT NULL,
    sort_order INT NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    PRIMARY KEY (tenant_id,shift_id,sort_order)
);

CREATE TABLE IF NOT EXISTS biz_staff_schedule_participant (
    tenant_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id,department_id,user_id),
    INDEX idx_staff_participant_user (tenant_id,user_id)
);

CREATE TABLE IF NOT EXISTS biz_staff_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    shift_id BIGINT NOT NULL,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_staff_assignment_day (tenant_id,user_id,work_date),
    INDEX idx_staff_assignment_store_date (tenant_id,department_id,work_date)
);
