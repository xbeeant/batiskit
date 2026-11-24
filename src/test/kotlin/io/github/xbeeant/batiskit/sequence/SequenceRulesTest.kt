package io.github.xbeeant.batiskit.sequence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 基础类 / 工具类过滤的纯逻辑回归：名单命中范围、不误伤业务代码与「仍应画箭头」的库类，
 * 以及解析不到包名时的简单名兜底。零平台依赖，不需要 IDE 夹具。
 */
class SequenceRulesTest {

    @Test
    fun `JDK 集合字符串与常见工具包命中基础类过滤`() {
        listOf(
            "java.lang.String",
            "java.util.List",
            "java.util.HashMap",
            "java.util.ArrayList",
            "java.util.Objects",
            "java.util.stream.Collectors",
            "javax.annotation.Nullable",
            "kotlin.collections.CollectionsKt",
            "kotlin.text.StringsKt",
            "org.springframework.util.CollectionUtils",
            "org.springframework.util.StringUtils",
            "org.apache.commons.collections4.CollectionUtils",
            "org.apache.commons.lang3.StringUtils",
            "com.google.common.collect.Lists",
            "cn.hutool.core.collection.CollectionUtil",
        ).forEach { assertTrue("$it 应被跳过", SequenceRules.isBasicClass(it)) }
    }

    @Test
    fun `业务类同名工具类与应保留的库类不被跳过`() {
        listOf(
            "com.example.service.UserService",
            "com.example.util.StringUtils",
            "com.example.CollectionUtils",
            "com.baomidou.mybatisplus.core.mapper.BaseMapper",
            "org.junit.Assert",
            "org.slf4j.Logger",
            "anonymous",
            "anonymous Runnable",
            "",
            null,
        ).forEach { assertFalse("$it 不应被跳过", SequenceRules.isBasicClass(it)) }
    }

    @Test
    fun `解析不到包名时按简单类名兜底`() {
        assertTrue(SequenceRules.isBasicClass("HashMap"))
        assertTrue(SequenceRules.isBasicClass("CollectionUtils"))
        assertTrue(SequenceRules.isBasicClass(null, "List"))
        assertTrue(SequenceRules.isBasicClass("", "StringUtils"))
        // 有简单名时以它为准，不被 simple 参数带偏
        assertFalse(SequenceRules.isBasicClass("UserService", "String"))
        assertFalse(SequenceRules.isBasicClass(null, null))
    }
}
