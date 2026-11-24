-- =====================================================================
-- 覆盖各类字段类型的示例建表语句（MySQL 5.7 / 8.0，可直接执行）
-- 用途：给 Batis Kit Code Generator 做生成测试。
-- 覆盖：整型 / 浮点 / 定点 / 字符串 / 日期时间 / 布尔 / 二进制 / JSON / 枚举 / 集合
-- =====================================================================

CREATE TABLE `t_order` (
    -- ---------- 主键 ----------
    `id`              BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '主键',

    -- ---------- 布尔（tinyint(1) → Boolean） ----------
    `is_deleted`      TINYINT(1)        NOT NULL DEFAULT 0 COMMENT '是否删除',
    `enabled`         BOOLEAN           NOT NULL DEFAULT TRUE COMMENT '是否启用',

    -- ---------- 整型 ----------
    `tiny_flag`       TINYINT           NOT NULL DEFAULT 0 COMMENT '小整型',
    `short_count`     SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '短整型',
    `medium_value`    MEDIUMINT         NOT NULL DEFAULT 0 COMMENT '中整型',
    `quantity`        INT               NOT NULL DEFAULT 0 COMMENT '数量',
    `big_number`      BIGINT            NOT NULL DEFAULT 0 COMMENT '大整数',
    `serial_no`       INTEGER           NULL COMMENT '兼容写法的整型',

    -- ---------- 浮点 / 定点 ----------
    `score`           FLOAT             NULL COMMENT '浮点分数',
    `ratio`           DOUBLE            NULL COMMENT '比例',
    `amount`          DECIMAL(12, 2)    NOT NULL DEFAULT 0.00 COMMENT '金额（→ BigDecimal）',
    `percent_value`   NUMERIC(5, 4)     NULL COMMENT '百分比（→ BigDecimal）',

    -- ---------- 定长 / 变长字符串 ----------
    `code`            CHAR(10)          NULL COMMENT '定长编码',
    `name`            VARCHAR(64)       NOT NULL COMMENT '名称（会生成 nameLike 之类模糊字段）',
    `user_nickname`   NVARCHAR(128)     NULL COMMENT '别名（MySQL 的 NATIONAL VARCHAR，映射同 varchar）',

    -- ---------- 大文本 ----------
    `remark`          TEXT              NULL COMMENT '备注（LONGVARCHAR，不生成模糊字段）',
    `content`         MEDIUMTEXT        NULL COMMENT '内容',
    `log_detail`      LONGTEXT          NULL COMMENT '日志详情',

    -- ---------- JSON ----------
    `ext_info`        JSON              NULL COMMENT '扩展信息（→ String）',

    -- ---------- 枚举 / 集合（→ String） ----------
    `status`          ENUM('INIT', 'PAID', 'DONE', 'CANCELLED') NOT NULL DEFAULT 'INIT' COMMENT '状态',
    `tags`            SET('A', 'B', 'C') NULL COMMENT '标签集合',

    -- ---------- 日期时间 ----------
    `birthday`        DATE              NULL COMMENT '生日（→ LocalDate / java.util.Date）',
    `created_time`    DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（→ LocalDateTime）',
    `updated_time`    TIMESTAMP         NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `begin_time`      TIME              NULL COMMENT '开始时刻（→ LocalTime）',
    `expire_time`     DATETIME          NULL COMMENT '过期时间',

    -- ---------- 二进制 ----------
    `avatar`          VARCHAR(512)      NULL COMMENT '头像 URL',
    `attachment`      BLOB              NULL COMMENT '附件（→ byte[]）',
    `big_attachment`  LONGBLOB          NULL COMMENT '大附件',
    `raw_bytes`       BINARY(16)        NULL COMMENT '定长二进制（如 UUID 二进制）',
    `checksum`        VARBINARY(64)     NULL COMMENT '校验值',

    -- ---------- 位 / 其他 ----------
    `permission_bits` BIT(8)            NULL COMMENT '权限位（多 bit → byte[]）',
    `row_version`     INT               NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_status_created` (`status`, `created_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '订单表（生成器测试用）';

-- ---------------------------------------------------------------------
-- 无主键表（生成器会跳过按主键的 CRUD，只保留 insert / 条件查询）
-- ---------------------------------------------------------------------
CREATE TABLE `t_access_log` (
    `trace_id`    VARCHAR(64)  NOT NULL COMMENT '链路 ID',
    `uri`         VARCHAR(255) NULL COMMENT '请求路径',
    `cost_ms`     INT          NOT NULL DEFAULT 0 COMMENT '耗时',
    `log_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '日志时间',
    `detail`      TEXT         NULL COMMENT '详情'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '访问日志表（无主键）';
