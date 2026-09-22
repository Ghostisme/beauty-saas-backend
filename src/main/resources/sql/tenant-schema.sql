-- Logical associations only: no foreign keys and no cascading constraints.
CREATE TABLE IF NOT EXISTS sys_tenant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(100) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_code (code)
);
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(100) NOT NULL,
    nickname VARCHAR(50) NOT NULL,
    phone VARCHAR(20),
    email VARCHAR(100),
    avatar VARCHAR(255),
    status TINYINT NOT NULL DEFAULT 1,
    auth_version BIGINT NOT NULL DEFAULT 1,
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_tenant_username (tenant_id, username),
    INDEX idx_user_tenant_status (tenant_id, deleted, status)
);
CREATE TABLE IF NOT EXISTS sys_tenant_admin (
    tenant_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    UNIQUE KEY uk_tenant_admin_user (tenant_id, user_id)
);
CREATE TABLE IF NOT EXISTS sys_department (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    parent_id BIGINT NOT NULL DEFAULT 0,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_department_code (tenant_id, code),
    INDEX idx_department_parent (tenant_id, parent_id)
);
CREATE TABLE IF NOT EXISTS sys_room (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    capacity INT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 1,
    remark VARCHAR(500),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_room_code (tenant_id, code)
);
CREATE TABLE IF NOT EXISTS sys_department_room (
    tenant_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, room_id),
    INDEX idx_department_room (tenant_id, department_id)
);
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(50) NOT NULL,
    builtin TINYINT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(500),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_role_code (tenant_id, code)
);
CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(60) NOT NULL,
    name VARCHAR(60) NOT NULL,
    module VARCHAR(40) NOT NULL,
    UNIQUE KEY uk_permission_code (code)
);
CREATE TABLE IF NOT EXISTS sys_role_permission (
    tenant_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, role_id, permission_id)
);
CREATE TABLE IF NOT EXISTS sys_user_department (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, user_id, department_id),
    INDEX idx_department_users (tenant_id, department_id, user_id)
);
CREATE TABLE IF NOT EXISTS sys_user_role (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, user_id, role_id, department_id),
    INDEX idx_role_users (tenant_id, role_id),
    INDEX idx_role_department (tenant_id, department_id)
);
