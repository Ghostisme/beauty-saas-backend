ALTER TABLE biz_staff_sop_check ADD COLUMN result_mode VARCHAR(16) NOT NULL DEFAULT 'COMPLETE';
ALTER TABLE biz_staff_sop_check ADD COLUMN result_value VARCHAR(1000) NULL;
