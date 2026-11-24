package io.github.xbeeant.batiskit.generate

/**
 * 框架基类（core / spring.mybatis 的 BaseModelObject）提供的持久化审计字段：属性名 -> (列名, Java 类型)。
 * 与 core 工程的 BaseModelObject 保持一致，勿随意增删。
 *
 * 用途：实体默认通过 ignoreFields 忽略这些列（由基类继承提供，不在实体里重复声明）；
 * 分层对象（DTO / VO / QueryDTO）没有基类，必须保留全字段；
 * 转换器映射这些字段时直接走基类 getter / setter，保证生成代码可编译。
 */
object BaseModelFields {

    val AUDIT: Map<String, Pair<String, String>> = linkedMapOf(
        "createAt" to ("create_at" to "LocalDateTime"),
        "creator" to ("creator" to "String"),
        "createBy" to ("create_by" to "String"),
        "updateAt" to ("update_at" to "LocalDateTime"),
        "updator" to ("updator" to "String"),
        "updateBy" to ("update_by" to "String"),
        "deleted" to ("deleted" to "Boolean"),
        "deleteAt" to ("delete_at" to "LocalDateTime"),
        "deleteOperator" to ("delete_operator" to "String"),
        "deleteBy" to ("delete_by" to "String")
    )

    /** 基类提供的属性名集合 */
    val FIELD_NAMES: Set<String> = AUDIT.keys
}
