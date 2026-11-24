---
id: pureMapperXml
name: Mapper XML（纯 MyBatis 全套 SQL）
group: 基础
order: 40
output: {mapperXmlDir}/{table.namingBase}Mapper.xml
root: resources
merge: xml
framework: mybatis
---
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
## 纯 MyBatis：每条 SQL 都写全，不依赖任何父类提供的语句；namespace 与 Mapper 接口的实际包名一致
#set($entityFqn = "${cfg.basePackage}.model.${entity}")
#set($queryFqn = "${cfg.basePackage}.dto.${table.namingBase}QueryDTO")
<mapper namespace="${cfg.basePackage}.mapper.${table.namingBase}Mapper">

    <resultMap id="BaseResultMap" type="${entityFqn}">
#foreach($c in $tableColumns)
        <#if($c.pk)id#end#if(!$c.pk)result#end column="${c.column}" jdbcType="${c.jdbcType}" property="${c.field}" />
#end
    </resultMap>

    <sql id="Base_Column_List">
        ${util.columnNames($tableColumns).join(", ")}
    </sql>

## 条件片段：字符串列忽略空串；日期列用 Begin / End 表达区间
    <sql id="Condition_Where_Clause">
#foreach($c in $tableColumns)
#if($c.dateLike)
        <if test="condition.${c.field}Begin != null">
            AND ${c.sqlName} &gt;= #{condition.${c.field}Begin,jdbcType=${c.jdbcType}}
        </if>
        <if test="condition.${c.field}End != null">
            AND ${c.sqlName} &lt;= #{condition.${c.field}End,jdbcType=${c.jdbcType}}
        </if>
#elseif($c.string)
        <if test="condition.${c.field} != null and condition.${c.field} != ''">
            AND ${c.sqlName} = #{condition.${c.field},jdbcType=${c.jdbcType}}
        </if>
#else
        <if test="condition.${c.field} != null">
            AND ${c.sqlName} = #{condition.${c.field},jdbcType=${c.jdbcType}}
        </if>
#end
#end
    </sql>

    <insert id="insert" parameterType="${entityFqn}">
        INSERT INTO ${table.name} (${util.columnNames($tableColumns).join(", ")})
        VALUES (${util.columnParams($tableColumns, "record.").join(", ")})
    </insert>

    <insert id="insertSelective" parameterType="${entityFqn}">
        INSERT INTO ${table.name}
        <trim prefix="(" suffix=")" suffixOverrides=",">
#foreach($c in $tableColumns)
            <if test="record.${c.field} != null">
                ${c.sqlName},
            </if>
#end
        </trim>
        <trim prefix="VALUES (" suffix=")" suffixOverrides=",">
#foreach($c in $tableColumns)
            <if test="record.${c.field} != null">
                #{record.${c.field},jdbcType=${c.jdbcType}},
            </if>
#end
        </trim>
    </insert>

    <insert id="batchInsert" parameterType="java.util.List">
        INSERT INTO ${table.name} (${util.columnNames($tableColumns).join(", ")})
        VALUES
        <foreach collection="records" item="item" separator=",">
            (${util.columnParams($tableColumns, "item.").join(", ")})
        </foreach>
    </insert>

#if($singlePk)
    <delete id="deleteById" parameterType="${pkParameterType}">
        DELETE FROM ${table.name}
        WHERE ${util.sqlColumn($singlePk)} = #{id,jdbcType=${singlePk.jdbcType}}
    </delete>

#end
    <delete id="deleteByCondition" parameterType="${queryFqn}">
        DELETE FROM ${table.name}
        <where>
            <include refid="Condition_Where_Clause" />
        </where>
    </delete>

#if($singlePk)
    <update id="updateById" parameterType="${entityFqn}">
        UPDATE ${table.name} SET
#foreach($c in $tableUpdatableColumns)
        ${c.sqlName} = #{record.${c.field},jdbcType=${c.jdbcType}}#if(!$foreach.last),#end
#end
        WHERE ${util.sqlColumn($singlePk)} = #{record.${singlePk.field},jdbcType=${singlePk.jdbcType}}
    </update>

    <update id="updateSelectiveById" parameterType="${entityFqn}">
        UPDATE ${table.name}
        <set>
#foreach($c in $tableUpdatableColumns)
            <if test="record.${c.field} != null">
                ${c.sqlName} = #{record.${c.field},jdbcType=${c.jdbcType}},
            </if>
#end
        </set>
        WHERE ${util.sqlColumn($singlePk)} = #{record.${singlePk.field},jdbcType=${singlePk.jdbcType}}
    </update>

#end
    <update id="updateByCondition">
        UPDATE ${table.name}
        <set>
#foreach($c in $tableUpdatableColumns)
            <if test="record.${c.field} != null">
                ${c.sqlName} = #{record.${c.field},jdbcType=${c.jdbcType}},
            </if>
#end
        </set>
        <where>
            <include refid="Condition_Where_Clause" />
        </where>
    </update>

#if($singlePk)
    <select id="selectById" parameterType="${pkParameterType}" resultMap="BaseResultMap">
        SELECT <include refid="Base_Column_List" />
        FROM ${table.name}
        WHERE ${util.sqlColumn($singlePk)} = #{id,jdbcType=${singlePk.jdbcType}}
    </select>

#end
    <select id="selectByCondition" parameterType="${queryFqn}" resultMap="BaseResultMap">
        SELECT <include refid="Base_Column_List" />
        FROM ${table.name}
        <where>
            <include refid="Condition_Where_Clause" />
        </where>
    </select>

    <select id="countByCondition" parameterType="${queryFqn}" resultType="java.lang.Long">
        SELECT COUNT(*) FROM ${table.name}
        <where>
            <include refid="Condition_Where_Clause" />
        </where>
    </select>

    <select id="selectPage" resultMap="BaseResultMap">
        SELECT <include refid="Base_Column_List" />
        FROM ${table.name}
        <where>
            <include refid="Condition_Where_Clause" />
        </where>
        ${util.pagination()}
    </select>
</mapper>
