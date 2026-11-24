---
id: purePlusMapper
name: Mapper 接口（纯 BaseMapper）
group: 基础
order: 30
output: {basePackageDir}/mapper/{table.namingBase}Mapper.java
root: java
merge: java
framework: mybatis-plus
---
package {package};

## 只继承 MyBatis-Plus 原生 BaseMapper：insert / deleteById / updateById / selectById / selectList
## / selectCount / delete / update / selectPage 全部由它提供，不需要任何自定义 SQL 或 XML
#foreach($fqn in $util.imports("${cfg.basePackage}.model.${entity}", "com.baomidou.mybatisplus.core.mapper.BaseMapper", "org.apache.ibatis.annotations.Mapper"))
import ${fqn};
#end

$util.javaDoc($table.comment, "Mapper 接口")
@Mapper
public interface ${table.namingBase}Mapper extends BaseMapper<${entity}> {
}
