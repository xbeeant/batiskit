package io.github.xbeeant.batiskit.config

/** 日期类型映射策略 */
enum class DateKind {
    /** java.time.LocalDate / LocalDateTime / LocalTime */
    LOCAL_DATE_TIME,

    /** java.util.Date */
    JAVA_UTIL_DATE
}

/** 目标框架：决定实体注解、Mapper / Service 的继承体系 */
enum class TargetFramework {
    /** 纯 MyBatis：完整生成 Mapper 接口 + XML + 手写 Service CRUD */
    MYBATIS,

    /** MyBatis-Plus：实体加 MP 注解，Mapper 继承 BaseMapper，Service 继承 IService / ServiceImpl */
    MYBATIS_PLUS
}

/** 生成 SQL 方言，影响分页与字符串拼接写法 */
enum class SqlDialect {
    MYSQL,
    POSTGRESQL,
    ORACLE
}

/** 配置对象的枚举读写扩展（配置文件里以字符串保存） */
var GenerationConfig.dateKindEnum: DateKind
    get() = runCatching { DateKind.valueOf(dateKind) }.getOrDefault(DateKind.LOCAL_DATE_TIME)
    set(value) {
        dateKind = value.name
    }

var GenerationConfig.dialectEnum: SqlDialect
    get() = runCatching { SqlDialect.valueOf(dialect) }.getOrDefault(SqlDialect.MYSQL)
    set(value) {
        dialect = value.name
    }

var GenerationConfig.frameworkEnum: TargetFramework
    get() = runCatching { TargetFramework.valueOf(targetFramework) }.getOrDefault(TargetFramework.MYBATIS)
    set(value) {
        targetFramework = value.name
    }
