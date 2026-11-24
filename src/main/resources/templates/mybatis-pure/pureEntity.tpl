---
id: pureEntity
name: 实体类（纯 POJO）
group: 基础
order: 10
output: {basePackageDir}/model/{entity}.java
root: java
merge: java
framework: mybatis
---
package {package};

## 纯 POJO：只实现 Serializable，不继承任何基类，也不带任何框架注解
#set($lombokImports = "")
#if($cfg.useLombok)
#set($lombokImports = "lombok.Getter,lombok.Setter")
#end
#foreach($fqn in $util.imports($tableColumns, "java.io.Serializable", $lombokImports))
import ${fqn};
#end

$util.javaDoc($table.comment, "实体类")
#if($cfg.useLombok)
@Getter
@Setter
#end
public class ${entity} implements Serializable {

    private static final long serialVersionUID = 1L;

#foreach($c in $tableColumns)
#if($c.comment)
    /**
     * $c.comment.replace("\n", " ")
     */
#end
    private ${c.type} ${c.field};

#end
#if(!$cfg.useLombok)
#foreach($c in $tableColumns)
$util.accessors($c)
#end
#end
}
