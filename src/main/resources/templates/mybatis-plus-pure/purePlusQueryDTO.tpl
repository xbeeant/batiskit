---
id: purePlusQueryDTO
name: QueryDTO（纯条件对象 / MyBatis-Plus）
group: 分层对象
order: 20
output: {basePackageDir}/dto/{table.namingBase}QueryDTO.java
root: java
merge: java
framework: mybatis-plus
---
package {package};

## 条件对象：每个列一个等值条件，日期列额外带 xxxBegin / xxxEnd 区间字段；
## 不继承任何查询基类，条件由 ServiceImpl 里的 QueryWrapper 消费
#set($lombokImports = "")
#if($cfg.useLombok)
#set($lombokImports = "lombok.Getter,lombok.Setter")
#end
#foreach($fqn in $util.imports($queryColumns, "java.io.Serializable", $lombokImports))
import ${fqn};
#end

$util.javaDoc($table.comment, "查询条件")
#if($cfg.useLombok)
@Getter
@Setter
#end
public class ${table.namingBase}QueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

#foreach($c in $queryColumns)
#if($c.comment)
    /**
     * $c.comment.replace("\n", " ")
     */
#end
    private ${c.type} ${c.field};

#end
#if(!$cfg.useLombok)
#foreach($c in $queryColumns)
$util.accessors($c)
#end
#end
}
