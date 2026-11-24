---
id: purePlusService
name: Service 接口（纯 CRUD / MyBatis-Plus）
group: 基础
order: 50
output: {basePackageDir}/service/I{table.namingBase}Service.java
root: java
merge: java
framework: mybatis-plus
---
package {package};

## 与纯 MyBatis 方案同一套方法签名，方便两套方案互相切换；不继承 IService 等任何基接口
#foreach($fqn in $util.imports("${cfg.basePackage}.model.${entity}", "${cfg.basePackage}.dto.${table.namingBase}QueryDTO", "java.util.List"))
import ${fqn};
#end

$util.javaDoc($table.comment, "业务接口")
public interface I${table.namingBase}Service {

    /** 新增（写入全部字段） */
    int insert(${entity} record);

    /** 选择性新增（只写非空字段） */
    int insertSelective(${entity} record);

    /** 批量新增 */
    int batchInsert(List<${entity}> records);

#if($singlePk)
    /** 按主键删除 */
    int deleteById(${key} id);

#end
    /** 按条件删除 */
    int deleteByCondition(${table.namingBase}QueryDTO condition);

#if($singlePk)
    /** 按主键全量更新 */
    int updateById(${entity} record);

    /** 按主键选择性更新（只更新非空字段） */
    int updateSelectiveById(${entity} record);

#end
    /** 按条件选择性更新 */
    int updateByCondition(${entity} record, ${table.namingBase}QueryDTO condition);

#if($singlePk)
    /** 按主键查询 */
    ${entity} selectById(${key} id);

#end
    /** 按条件查询 */
    List<${entity}> selectByCondition(${table.namingBase}QueryDTO condition);

    /** 按条件统计 */
    long countByCondition(${table.namingBase}QueryDTO condition);

    /** 按条件分页查询（pageNum 从 1 开始，需要配置分页插件） */
    List<${entity}> selectPage(${table.namingBase}QueryDTO condition, int pageNum, int pageSize);
}
