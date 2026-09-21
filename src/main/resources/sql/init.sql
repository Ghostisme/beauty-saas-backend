-- 明确 SQL 文件的输入编码，避免客户端将 UTF-8 中文按 latin1 解读后写入。
SET NAMES utf8mb4;

-- 创建数据库
CREATE DATABASE IF NOT EXISTS beauty_saas DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE beauty_saas;

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT '密码（MD5加密）',
    nickname VARCHAR(50) COMMENT '昵称',
    phone VARCHAR(20) COMMENT '手机号',
    email VARCHAR(100) COMMENT '邮箱',
    avatar VARCHAR(255) COMMENT '头像URL',
    status TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    INDEX idx_username (username),
    INDEX idx_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 插入测试用户（密码：admin123，MD5加密后）
INSERT INTO sys_user (username, password, nickname, avatar, status)
VALUES ('admin', '0192023a7bbd73250516f069df18b500', '管理员', NULL, 1);
