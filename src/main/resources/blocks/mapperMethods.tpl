
#if($hasQuery)
    /**
     * 条件查询
     */
    List<$entity> selectByExample(@Param("example") $entity example);

    /**
     * 条件统计
     */
    long countByExample(@Param("example") $entity example);

    /**
     * 模糊搜索
     */
    List<$entity> fuzzySearch(@Param("example") $entity example);

    /**
     * 分页模糊搜索
     */
    List<$entity> fuzzySearchByPager(@Param("example") $entity example);

#end
#if($generateCrud && $hasPk)
    /**
     * 按主键查询
     */
    $entity selectByPrimaryKey(@Param("key") $util.pkPlainParams());

    /**
     * 按主键删除
     */
    int deleteByPrimaryKey(@Param("key") $util.pkPlainParams());

#end
#if($generateCrud)
    /**
     * 新增
     */
    int insert($entity record);

    /**
     * 选择性新增（只写非空字段）
     */
    int insertSelective($entity record);

#end
#if($generateCrud && $hasBatch)
    /**
     * 批量选择性新增（每条只写非空字段）
     */
    int batchInsertSelective(@Param("list") List<$entity> list);

#end
#if($generateCrud && $hasPk)
    /**
     * 按条件选择性更新
     */
    int updateByExampleSelective(@Param("record") $entity record, @Param("example") $entity example);

    /**
     * 按条件全量更新
     */
    int updateByExample(@Param("record") $entity record, @Param("example") $entity example);

    /**
     * 按主键选择性更新（只更新非空字段）
     */
    int updateByPrimaryKeySelective($entity record);

    /**
     * 按主键全量更新
     */
    int updateByPrimaryKey($entity record);

#end
#if($generateCrud && $hasBatchByPk)
    /**
     * 批量按主键删除（多语句，分号分隔）
     */
    int batchDeleteByPrimaryKey(@Param("items") List<${idType}> items);

    /**
     * 批量选择性更新（每条只更新非空字段，多语句，分号分隔）
     */
    int batchUpdateByPrimaryKeySelective(@Param("list") List<$entity> list);

#end
