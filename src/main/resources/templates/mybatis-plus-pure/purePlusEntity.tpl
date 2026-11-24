---
id: purePlusEntity
name: 实体类（纯 MyBatis-Plus POJO）
group: 基础
order: 10
output: {basePackageDir}/model/{entity}.java
root: java
merge: java
framework: mybatis-plus
---
package {package};

## 只用 MyBatis-Plus 自己的注解，不继承任何业务基类；无需 XML 即可映射表
#set($fixed = "java.io.Serializable,com.baomidou.mybatisplus.annotation.TableName,com.baomidou.mybatisplus.annotation.TableId,com.baomidou.mybatisplus.annotation.IdType,com.baomidou.mybatisplus.annotation.TableField")
#if($hasVersion)
#set($fixed = "$fixed,com.baomidou.mybatisplus.annotation.Version")
#end
#set($lombokImports = "")
#if($cfg.useLombok)
#set($lombokImports = "lombok.Getter,lombok.Setter")
#end
#foreach($fqn in $util.imports($tableColumns, $fixed, $lombokImports))
import ${fqn};
#end

$util.javaDoc($table.comment, "实体类")
#if($cfg.useLombok)
@Getter
@Setter
#end
@TableName("${table.name}")
public class ${entity} implements Serializable {

    private static final long serialVersionUID = 1L;

#foreach($c in $tableColumns)
#if($c.comment)
    /**
     * $c.comment.replace("\n", " ")
     */
#end
#if($c.version)
    @Version
#end
#if($c.pk)
    @TableId(value = "${c.column}", type = #if($c.auto)IdType.AUTO#end#if(!$c.auto)IdType.ASSIGN_ID#end)
#else
    @TableField("${c.column}")
#end
    private ${c.type} ${c.field};

#end
#if(!$cfg.useLombok)
#foreach($c in $tableColumns)
$util.accessors($c)
#end
#end
}
