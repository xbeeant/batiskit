---
id: pureServiceImpl
name: Service 实现（纯 CRUD）
group: 基础
order: 60
output: {basePackageDir}/service/impl/{table.namingBase}ServiceImpl.java
root: java
merge: java
framework: mybatis
---
package {package};

## 只注入自己的 Mapper，逐个实现接口方法；不继承任何 ServiceImpl 基类
#set($mapper = "${table.namingBase}Mapper")
#set($service = "I${table.namingBase}Service")
#foreach($fqn in $util.imports("${cfg.basePackage}.model.${entity}", "${cfg.basePackage}.mapper.${mapper}", "${cfg.basePackage}.service.${service}", "${cfg.basePackage}.dto.${table.namingBase}QueryDTO", "java.util.List", "org.springframework.stereotype.Service", "${injectionImport}"))
import ${fqn};
#end

$util.javaDoc($table.comment, "业务实现")
@Service
public class ${table.namingBase}ServiceImpl implements ${service} {

    ${injectionAnnotation}
    private ${mapper} ${mapper.uncap()};

    @Override
    public int insert(${entity} record) {
        return ${mapper.uncap()}.insert(record);
    }

    @Override
    public int insertSelective(${entity} record) {
        return ${mapper.uncap()}.insertSelective(record);
    }

    @Override
    public int batchInsert(List<${entity}> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        return ${mapper.uncap()}.batchInsert(records);
    }

#if($singlePk)
    @Override
    public int deleteById(${key} id) {
        return ${mapper.uncap()}.deleteById(id);
    }

#end
    @Override
    public int deleteByCondition(${table.namingBase}QueryDTO condition) {
        return ${mapper.uncap()}.deleteByCondition(condition);
    }

#if($singlePk)
    @Override
    public int updateById(${entity} record) {
        return ${mapper.uncap()}.updateById(record);
    }

    @Override
    public int updateSelectiveById(${entity} record) {
        return ${mapper.uncap()}.updateSelectiveById(record);
    }

#end
    @Override
    public int updateByCondition(${entity} record, ${table.namingBase}QueryDTO condition) {
        return ${mapper.uncap()}.updateByCondition(record, condition);
    }

#if($singlePk)
    @Override
    public ${entity} selectById(${key} id) {
        return ${mapper.uncap()}.selectById(id);
    }

#end
    @Override
    public List<${entity}> selectByCondition(${table.namingBase}QueryDTO condition) {
        return ${mapper.uncap()}.selectByCondition(condition);
    }

    @Override
    public long countByCondition(${table.namingBase}QueryDTO condition) {
        return ${mapper.uncap()}.countByCondition(condition);
    }

    @Override
    public List<${entity}> selectPage(${table.namingBase}QueryDTO condition, int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.max(pageSize, 1);
        return ${mapper.uncap()}.selectPage(condition, (page - 1) * size, size);
    }
}
