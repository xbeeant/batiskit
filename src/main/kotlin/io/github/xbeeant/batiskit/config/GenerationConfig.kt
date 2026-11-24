package io.github.xbeeant.batiskit.config

/**
 * 内置 SQL 保留字默认值（MySQL / PostgreSQL / Oracle 的高频交集与常见列名冲突词）：
 * 非 MySQL 方言下列名命中时加双引号；同时也是「SQL 保留字」配置项的默认 tag 列表
 */
const val DEFAULT_SQL_RESERVED_KEYWORDS =
    "select,from,where,insert,update,delete,order,group,by,having," +
        "and,or,not,in,is,null,like,between,case,when,then,else,end," +
        "union,all,distinct,limit,offset,join,inner,outer,left,right,full,on,as," +
        "key,index,type,desc,asc,condition,default,primary,unique,foreign," +
        "table,column,create,drop,alter,check,references,trigger,procedure," +
        "function,grant,revoke,lock,unlock,partition,range,rows,values,value," +
        "set,use,level,mode,read,write,user,position,comment,interval," +
        "year,month,day,hour,minute,second,zone,text,name,status,usage," +
        "system,password,operator,action,domain,number,size"

/**
 * 生成器配置。
 *
 * ## 只剩「跟表结构和命名有关」的东西
 *
 * 包结构、基类、swagger / 事务 / 批量方法 / 日期区间 / 主键方法 / serialVersionUID 这些
 * **产物内容**一律不进配置——它们写死在模板里（`templates/<方案>/` 下的 tpl 文件），想改就改模板。
 * 唯一的例外是 lombok：它横跨实体 / DTO / VO / QueryDTO 四类产物且模板分支写法一致，
 * 值得留在配置里（`useLombok`，模板里用 `$cfg.useLombok` 分支）。
 * 所以这个类里剩下的都是「生成器替你决定不了、但每条命令都可能不同」的信息：
 * 基础包名、表前缀、类名后缀、作者、注入风格、忽略字段、方言。
 *
 * ## 为什么是纯字段类
 *
 * 平台 [com.intellij.util.xmlb.XmlSerializer] 直接序列化这个类；枚举相关字段用 String 存放，
 * 通过 [ConfigExt] 里的扩展属性读写，避免序列化器处理 enum 的兼容问题。
 */
class GenerationConfig {

    /** 目标模块（module name），为空表示需要用户在弹窗里选择 */
    var moduleName: String = ""

    /** 基础包名：模板里的 `{basePackageDir}` 由它变成目录，包名由模板的 output 路径推导 */
    var basePackage: String = "io.github.xbeeant"

    /** 生成类名时需要去掉的表前缀，逗号分隔，例如 "t_,sys_" */
    var tablePrefixes: String = "t_"

    /**
     * 实体名重命名规则（对应 MBG DomainObjectRenamingRule / 原插件 Search String、Replace String）：
     * searchString 非空时，把表名中的 searchString 替换为 replaceString 后再生成类名
     */
    var searchString: String = ""
    var replaceString: String = ""

    /** 实体类后缀，例如 "DO" */
    var entitySuffix: String = ""

    /** 库名（对应参考插件 projectProperties.database），用于 XML 输出路径 `mybatis/数据库类型/库名` */
    var databaseName: String = ""

    /** 作者，写在 JavaDoc 的 @author */
    var author: String = System.getProperty("user.name", "")

    /** Mapper 注入方式：JAKARTA_RESOURCE / JAVAX_RESOURCE / AUTOWIRED */
    var injectionStyle: String = "JAKARTA_RESOURCE"

    /**
     * 不生成到实体/查询/Mapper 里的审计字段（列名或 Java 字段名，逗号分隔），
     * 默认值来自 idea-batiskit-plugin 的 ignoreColumns
     */
    var ignoreFields: String =
        "create_by,update_by,create_at,update_at,creator,updator,delete_at,delete_by,delete_operator,deleted,version"

    /** 不参与模糊查询的列（列名或字段名，逗号分隔），默认值来自参考插件 nonFuzzySearchColumn */
    var nonFuzzySearchColumns: String = "create_by, update_by,delete_by"

    /**
     * SQL 保留字（逗号分隔，配置弹窗里以 tag 标签形式编辑）：
     * 非 MySQL 方言下列名命中时加双引号；留空恢复内置默认值
     */
    var sqlReservedKeywords: String = DEFAULT_SQL_RESERVED_KEYWORDS

    /**
     * 同签名方法覆盖：合并时发现已有方法的实现与本次生成不一致时，用新实现替换旧方法。
     * 关闭则维持「只补缺不覆盖」的保护策略（用户手改过的方法不会被冲掉）
     */
    var overwriteMethods: Boolean = true

    /** 日期类型：LOCAL_DATE_TIME / JAVA_UTIL_DATE */
    var dateKind: String = "LOCAL_DATE_TIME"

    /**
     * 启用 Lombok：模板里实体 / DTO / VO / QueryDTO 用类级 @Getter / @Setter 代替手写访问器，
     * 关闭则照旧展开 getter / setter。模板里用 `$cfg.useLombok` 分支，自建模板同样可读
     */
    var useLombok: Boolean = true

    /** SQL 方言：MYSQL / POSTGRESQL / ORACLE */
    var dialect: String = "MYSQL"

    /** 目标框架：MYBATIS / MYBATIS_PLUS */
    var targetFramework: String = "MYBATIS"

    /**
     * 使用的模板方案（套）：空表示跟随 [targetFramework] 的文件夹。
     *
     * 一套方案就是 `templates/` 下的一个文件夹（一份完整的产物清单），选定后跑该文件夹下的全部模板。
     */
    var templateSet: String = ""

    /** 写入前展示变更预览（可逐文件 / 逐模板取消）。关闭则点确定直接写盘 */
    var previewBeforeWrite: Boolean = true

    /** 给新建的 Java 产物加 `@Generated` 来源标记（方案、模板 id），便于区分手改代码 */
    var markGenerated: Boolean = true

    /** 不生成的模板 key：`方案:模板id`，逗号分隔；由生成预览里的模板清单维护 */
    var disabledTemplates: String = ""

    fun copyOf(): GenerationConfig = GenerationConfig().also { c ->
        c.moduleName = moduleName
        c.basePackage = basePackage
        c.tablePrefixes = tablePrefixes
        c.searchString = searchString
        c.replaceString = replaceString
        c.entitySuffix = entitySuffix
        c.databaseName = databaseName
        c.author = author
        c.injectionStyle = injectionStyle
        c.ignoreFields = ignoreFields
        c.nonFuzzySearchColumns = nonFuzzySearchColumns
        c.sqlReservedKeywords = sqlReservedKeywords
        c.overwriteMethods = overwriteMethods
        c.dateKind = dateKind
        c.useLombok = useLombok
        c.dialect = dialect
        c.targetFramework = targetFramework
        c.templateSet = templateSet
        c.previewBeforeWrite = previewBeforeWrite
        c.markGenerated = markGenerated
        c.disabledTemplates = disabledTemplates
    }
}
