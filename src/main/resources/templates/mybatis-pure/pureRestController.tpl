---
id: pureRestController
name: REST 接口（纯 CRUD）
group: 基础
order: 70
output: {basePackageDir}/rest/{table.namingBase}RestController.java
root: java
merge: java
framework: mybatis
---
package {package};

## 端点直接调用 Service，不继承任何 Controller 基类，也不依赖统一响应包装类
#set($service = "I${table.namingBase}Service")
#set($serviceField = "${table.namingBase}Service")
#foreach($fqn in $util.imports("${cfg.basePackage}.model.${entity}", "${cfg.basePackage}.service.${service}", "${cfg.basePackage}.dto.${table.namingBase}QueryDTO", "java.util.List", "org.springframework.web.bind.annotation.DeleteMapping", "org.springframework.web.bind.annotation.GetMapping", "org.springframework.web.bind.annotation.PathVariable", "org.springframework.web.bind.annotation.PostMapping", "org.springframework.web.bind.annotation.PutMapping", "org.springframework.web.bind.annotation.RequestBody", "org.springframework.web.bind.annotation.RequestMapping", "org.springframework.web.bind.annotation.RequestParam", "org.springframework.web.bind.annotation.RestController", "${injectionImport}"))
import ${fqn};
#end

$util.javaDoc($table.comment, "REST 接口")
@RestController
@RequestMapping("${controllerPath}")
public class ${table.namingBase}RestController {

    ${injectionAnnotation}
    private ${service} ${serviceField.uncap()};

    @PostMapping
    public int insert(@RequestBody ${entity} record) {
        return ${serviceField.uncap()}.insert(record);
    }

    @PostMapping("/selective")
    public int insertSelective(@RequestBody ${entity} record) {
        return ${serviceField.uncap()}.insertSelective(record);
    }

    @PostMapping("/batch")
    public int batchInsert(@RequestBody List<${entity}> records) {
        return ${serviceField.uncap()}.batchInsert(records);
    }

#if($singlePk)
    @DeleteMapping("/{id}")
    public int deleteById(@PathVariable("id") ${key} id) {
        return ${serviceField.uncap()}.deleteById(id);
    }

#end
    @PostMapping("/delete")
    public int deleteByCondition(@RequestBody ${table.namingBase}QueryDTO condition) {
        return ${serviceField.uncap()}.deleteByCondition(condition);
    }

#if($singlePk)
    @PutMapping
    public int updateById(@RequestBody ${entity} record) {
        return ${serviceField.uncap()}.updateById(record);
    }

    @PutMapping("/selective")
    public int updateSelectiveById(@RequestBody ${entity} record) {
        return ${serviceField.uncap()}.updateSelectiveById(record);
    }

#end
## 按条件更新的两个入参无法共用一个 @RequestBody，端点不暴露；Service 层已提供 updateByCondition
#if($singlePk)
    @GetMapping("/{id}")
    public ${entity} selectById(@PathVariable("id") ${key} id) {
        return ${serviceField.uncap()}.selectById(id);
    }

#end
    @PostMapping("/list")
    public List<${entity}> selectByCondition(@RequestBody(required = false) ${table.namingBase}QueryDTO condition) {
        return ${serviceField.uncap()}.selectByCondition(condition);
    }

    @PostMapping("/count")
    public long countByCondition(@RequestBody(required = false) ${table.namingBase}QueryDTO condition) {
        return ${serviceField.uncap()}.countByCondition(condition);
    }

    @GetMapping("/page")
    public List<${entity}> selectPage(${table.namingBase}QueryDTO condition,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ${serviceField.uncap()}.selectPage(condition, pageNum, pageSize);
    }
}
