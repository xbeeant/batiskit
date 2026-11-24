---
id: purePlusServiceImpl
name: Service 实现（纯 BaseMapper + QueryWrapper）
group: 基础
order: 60
output: {basePackageDir}/service/impl/{table.namingBase}ServiceImpl.java
root: java
merge: java
framework: mybatis-plus
---
package {package};

## 只注入自己的 Mapper；CRUD 调 BaseMapper，条件查询用 QueryWrapper 现拼，不继承 ServiceImpl 基类
#set($mapper = "${table.namingBase}Mapper")
#set($service = "I${table.namingBase}Service")
#foreach($fqn in $util.imports("${cfg.basePackage}.model.${entity}", "${cfg.basePackage}.mapper.${mapper}", "${cfg.basePackage}.service.${service}", "${cfg.basePackage}.dto.${table.namingBase}QueryDTO", "java.util.List", "org.springframework.stereotype.Service", "${injectionImport}", "com.baomidou.mybatisplus.core.conditions.query.QueryWrapper", "com.baomidou.mybatisplus.extension.plugins.pagination.Page"))
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
        // BaseMapper.insert 默认按 NOT_NULL 字段策略写入，等价于选择性新增
        return ${mapper.uncap()}.insert(record);
    }

    @Override
    public int batchInsert(List<${entity}> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        int rows = 0;
        for (${entity} record : records) {
            rows += ${mapper.uncap()}.insert(record);
        }
        return rows;
    }

#if($singlePk)
    @Override
    public int deleteById(${key} id) {
        return ${mapper.uncap()}.deleteById(id);
    }

#end
    @Override
    public int deleteByCondition(${table.namingBase}QueryDTO condition) {
        return ${mapper.uncap()}.delete(wrapperOf(condition));
    }

#if($singlePk)
    @Override
    public int updateById(${entity} record) {
        return ${mapper.uncap()}.updateById(record);
    }

    @Override
    public int updateSelectiveById(${entity} record) {
        // updateById 默认只更新非 null 字段
        return ${mapper.uncap()}.updateById(record);
    }

#end
    @Override
    public int updateByCondition(${entity} record, ${table.namingBase}QueryDTO condition) {
        return ${mapper.uncap()}.update(record, wrapperOf(condition));
    }

#if($singlePk)
    @Override
    public ${entity} selectById(${key} id) {
        return ${mapper.uncap()}.selectById(id);
    }

#end
    @Override
    public List<${entity}> selectByCondition(${table.namingBase}QueryDTO condition) {
        return ${mapper.uncap()}.selectList(wrapperOf(condition));
    }

    @Override
    public long countByCondition(${table.namingBase}QueryDTO condition) {
        // longValue 兼容 MP 3.4 的 Integer 与 3.5+ 的 Long 返回值
        return ${mapper.uncap()}.selectCount(wrapperOf(condition)).longValue();
    }

    @Override
    public List<${entity}> selectPage(${table.namingBase}QueryDTO condition, int pageNum, int pageSize) {
        Page<${entity}> page = new Page<>(Math.max(pageNum, 1), Math.max(pageSize, 1));
        return ${mapper.uncap()}.selectPage(page, wrapperOf(condition)).getRecords();
    }

    /** 把 QueryDTO 的非空字段拼成等值 / 区间条件；传入 null 表示不过滤 */
    private QueryWrapper<${entity}> wrapperOf(${table.namingBase}QueryDTO condition) {
        QueryWrapper<${entity}> wrapper = new QueryWrapper<>();
        if (condition == null) {
            return wrapper;
        }
#foreach($c in $tableColumns)
#if($c.dateLike)
        if (condition.get${c.cap}Begin() != null) {
            wrapper.ge("${c.column}", condition.get${c.cap}Begin());
        }
        if (condition.get${c.cap}End() != null) {
            wrapper.le("${c.column}", condition.get${c.cap}End());
        }
#elseif($c.string)
        if (condition.get${c.cap}() != null && !condition.get${c.cap}().isEmpty()) {
            wrapper.eq("${c.column}", condition.get${c.cap}());
        }
#else
        if (condition.get${c.cap}() != null) {
            wrapper.eq("${c.column}", condition.get${c.cap}());
        }
#end
#end
        return wrapper;
    }
}
