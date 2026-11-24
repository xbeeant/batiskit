---
id: pureMapper
name: Mapper 接口（纯 MyBatis）
group: 基础
order: 30
output: {basePackageDir}/mapper/{table.namingBase}Mapper.java
root: java
merge: java
framework: mybatis
---
package {package};

## 全部 CRUD 方法显式声明，SQL 由 pureMapperXml 提供；不继承任何 Dao 基类
#foreach($fqn in $util.imports("${cfg.basePackage}.model.${entity}", "${cfg.basePackage}.dto.${table.namingBase}QueryDTO", "java.util.List", "org.apache.ibatis.annotations.Mapper", "org.apache.ibatis.annotations.Param"))
import ${fqn};
#end

$util.javaDoc($table.comment, "Mapper 接口")
@Mapper
public interface ${table.namingBase}Mapper {

    /** 新增（写入全部字段） */
    int insert(@Param("record") ${entity} record);

    /** 选择性新增（只写非空字段） */
    int insertSelective(@Param("record") ${entity} record);

    /** 批量新增 */
    int batchInsert(@Param("records") List<${entity}> records);

#if($singlePk)
    /** 按主键删除 */
    int deleteById(@Param("id") ${key} id);

#end
    /** 按条件删除 */
    int deleteByCondition(@Param("condition") ${table.namingBase}QueryDTO condition);

#if($singlePk)
    /** 按主键全量更新 */
    int updateById(@Param("record") ${entity} record);

    /** 按主键选择性更新（只更新非空字段） */
    int updateSelectiveById(@Param("record") ${entity} record);

#end
    /** 按条件选择性更新 */
    int updateByCondition(@Param("record") ${entity} record, @Param("condition") ${table.namingBase}QueryDTO condition);

#if($singlePk)
    /** 按主键查询 */
    ${entity} selectById(@Param("id") ${key} id);

#end
    /** 按条件查询 */
    List<${entity}> selectByCondition(@Param("condition") ${table.namingBase}QueryDTO condition);

    /** 按条件统计 */
    long countByCondition(@Param("condition") ${table.namingBase}QueryDTO condition);

    /** 按条件分页查询（offset 从 0 开始） */
    List<${entity}> selectPage(@Param("condition") ${table.namingBase}QueryDTO condition, @Param("offset") int offset, @Param("pageSize") int pageSize);
}
