package io.github.xbeeant.batiskit.intention

import io.github.xbeeant.batiskit.settings.ModuleToggles

/**
 * 编辑器右键动作 → Alt+Enter intention 的映射目录（纯逻辑，可在无 IDE 容器时校验）。
 *
 * 右键菜单的全部入口都声明在 `plugin.xml` 的 `BatisKit.RootGroup` 里。让 Alt+Enter
 * 与右键保持**逐项一致**：
 *
 * 1. [DIRECT] 里的顶层动作各自包一层 [EditorActionIntention]，直接出现在 Alt+Enter
 *    列表里，文案（含 update() 动态改写的）、可用性与右键菜单完全一致。
 * 2. [DEDICATED] 里的动作早就有自己的 intention（GenerateAllSetter / String Manipulation
 *    的「搜索全部转换…」），再包一层只会在列表里出现两条重复项，刻意跳过。
 * 3. [SUBMENUS] 里的三个子菜单各包一层 [EditorActionGroupIntention]，选中后弹出与右键
 *    完全同一份 group；子菜单名与右键逐字一致，以后往子菜单里加动作自动跟上。
 *
 * 模块开关沿用 [ModuleToggles]：某个模块被关掉时，它的右键入口本就隐藏，对应
 * intention 也必须一起隐藏，不能绕过设置。
 */
object EditorActionIds {

    /** `plugin.xml` 里统一右键父菜单的 id */
    const val ROOT_GROUP = "BatisKit.RootGroup"

    /** Settings → Editor → Intentions 里的分组名，也是所有新 intention 的 familyName */
    const val FAMILY = "Batis Kit"

    /** RootGroup 里 `<reference>` 的全部 id，与 plugin.xml 逐字对应 */
    val ROOT_REFERENCES: List<String> = listOf(
        "BatisKit.JsonToPojo",
        "BatisKit.FormatMapperXml",
        "BatisKit.RestoreMyBatisSql",
        "BatisKit.ToggleRainbowBrackets",
        "BatisKit.HighlightBracketScope",
        "BatisKit.ClearBracketScope",
        "BatisKit.GenerateAllSetter",
        "BatisKit.TemplateWorkbench",
        "BatisKit.SequenceDiagram",
        "BatisKit.StringManipulationChooser",
        "BatisKit.StringManipulation",
        "BatisKit.GitLinkGroup",
        "BatisKit.GitToolboxGroup",
    )

    /** 已有专属 intention 的动作，不再包装；否则 Alt+Enter 里会出现两条一样的项 */
    val DEDICATED: Set<String> = setOf(
        "BatisKit.GenerateAllSetter",           // setter/GenerateAllSetterIntention
        "BatisKit.StringManipulationChooser",   // string/StringManipulationIntention（「搜索全部转换…」）
    )

    /** 由子菜单 intention 弹出的 popup；它们自己不是可执行动作 */
    val SUBMENUS: Set<String> = setOf(
        "BatisKit.StringManipulation",
        "BatisKit.GitLinkGroup",
        "BatisKit.GitToolboxGroup",
    )

    /** 直接注册成 `<intentionAction>` 的顶层动作 */
    val DIRECT: List<String> = listOf(
        "BatisKit.JsonToPojo",
        "BatisKit.FormatMapperXml",
        "BatisKit.RestoreMyBatisSql",
        "BatisKit.ToggleRainbowBrackets",
        "BatisKit.HighlightBracketScope",
        "BatisKit.ClearBracketScope",
        "BatisKit.TemplateWorkbench",
        "BatisKit.SequenceDiagram",
    )

    /** 直接 intention 的类名（供测试核对 plugin.xml 注册） */
    val DIRECT_INTENTION_CLASSES: List<String> = listOf(
        "JsonToPojoIntention",
        "FormatMapperXmlIntention",
        "RestoreMyBatisSqlIntention",
        "ToggleRainbowBracketsIntention",
        "HighlightBracketScopeIntention",
        "ClearBracketScopeIntention",
        "TemplateWorkbenchIntention",
        "SequenceDiagramIntention",
    )

    /** 三个子菜单 intention 的类名（供测试核对 plugin.xml 注册） */
    val SUBMENU_INTENTION_CLASSES: List<String> = listOf(
        "StringManipulationGroupIntention",
        "GitLinkGroupIntention",
        "GitToolboxGroupIntention",
    )

    /** 单个动作是否随所属模块一起启用；未知 id 不做过滤 */
    fun moduleEnabled(actionId: String): Boolean =
        ModuleToggles.moduleOfActionId(actionId)?.let { ModuleToggles.isEnabled(it) } ?: true

    /** RootGroup 的某项是否已被 Alt+Enter 覆盖（直接 intention / 专属 intention / 子菜单 intention） */
    fun isCovered(actionId: String): Boolean =
        actionId in DIRECT || actionId in DEDICATED || actionId in SUBMENUS
}
