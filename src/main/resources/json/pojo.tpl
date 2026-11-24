## JSON -> Java 类模板。
## 由 JsonRenderer 提供：className / kind / modifier / implementsClause / javaDoc / classAnnotations / fields / innerClasses
## fields[i]：name / type / modifier / comment / annotations
## 在「设置 -> Tools -> JSON → Java 对象」的「自定义类模板」里贴一份即可整体覆盖本文件。
#if($javaDoc != "")
/**
 * ${javaDoc}
 */
#end
#foreach($a in $classAnnotations)
$a
#end
#if($kind == "record")
public ${modifier}record ${className}(
#foreach($f in $fields)
#foreach($a in $f.annotations)
    ${a}
#end
    ${f.type} ${f.name}#if(!$foreach.last),#end
#end
) {
#else
public ${modifier}class ${className}${implementsClause} {
#if($serialVersionUid)

    private static final long serialVersionUID = 1L;
#end
#foreach($f in $fields)

#if($f.comment != "")
    /** ${f.comment} */
#end
#foreach($a in $f.annotations)
    ${a}
#end
    ${f.modifier}${f.type} ${f.name};
#end
#end
#if($innerClasses != "")

${innerClasses}
#end
}
