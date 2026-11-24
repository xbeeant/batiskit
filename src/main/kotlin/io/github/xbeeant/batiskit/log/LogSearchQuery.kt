package io.github.xbeeant.batiskit.log

import io.github.xbeeant.batiskit.util.SearchQuery

/**
 * MyBatis Log 工具窗口的搜索入口。
 *
 * 表达式实现已上移到通用的 [SearchQuery]（多个工具窗口与表格共用），这里保留原来的
 * `LogSearchQuery.parse/matches/Query` 签名，避免改动调用方与既有测试。
 */
object LogSearchQuery {

    typealias Query = SearchQuery.Query

    fun parse(text: String): Query = SearchQuery.parse(text)

    fun matches(text: String, query: Query): Boolean = SearchQuery.matches(text, query)
}
