package io.github.xbeeant.batiskit.ui

import com.intellij.ide.actions.ShowFilePathAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.config.DateKind
import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.config.SqlDialect
import io.github.xbeeant.batiskit.config.TargetFramework
import io.github.xbeeant.batiskit.config.dateKindEnum
import io.github.xbeeant.batiskit.config.dialectEnum
import io.github.xbeeant.batiskit.config.frameworkEnum
import io.github.xbeeant.batiskit.template.ProjectTemplates
import io.github.xbeeant.batiskit.template.TemplateRegistry
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** 下拉项：值 + 展示文本 */
data class Choice(val value: String, val label: String) {
    override fun toString(): String = label
}

/**
 * 生成配置表单（弹窗与设置页共用）。
 *
 * 布局选型（彻底重写）：
 *  - 行 = BorderLayout（标签 WEST + 控件 CENTER）：行高恒定 = 控件高 + 8，标签左置省一半纵向空间；
 *  - 纵向堆叠 = BoxLayout(Y_AXIS)：严格按子组件 preferred 高度排列——
 *    之前 FormBuilder 内部的 GridLayoutManager 实现了 Scrollable（视口追踪/行拉伸），
 *    与 JBScrollPane 叠加后 preferred 高度与实际渲染高度不一致，反复出现"最后一行只显示一半"；
 *  - 每个 Tab = JBScrollPane（NORTH 锚定 + 底部 16px）：内容贴顶，永不与视口边缘齐平；
 *  - 弹窗固定 740 × 640（含标题/按钮约 750，768 小屏完整显示），两个 Tab 的内容均小于视口，全程无滚动。
 *
 * 布局按「修改频率」分为两个 Tab：
 *  - 生成：每次生成都可能调整的项（模块、包名、模板方案 / 目标框架）
 *  - 代码风格：偶尔调整的项（命名、字段与注入、写入策略、SQL 保留字）
 *
 * 代码风格相关的「行为开关」（swagger / 事务 / 批量方法 / 基类 / 覆盖 XML…）已经全部下线：
 * 这些内容由模板正文直接书写，配置只管跟表结构、命名、文件落点有关的部分。
 * 例外是 lombok（[GenerationConfig.useLombok]）：内置模板按 `$cfg.useLombok` 分支，
 * 用类级 @Getter / @Setter 代替手写访问器，默认开启。
 *
 * @param preview 可选的预览组件（如「待生成的表」表格），嵌入「生成」Tab 顶部随 Tab 滚动
 * @param basePath 项目根目录提供者：项目模板（`.batiskit/templates/`）靠它定位。
 *   设置页是**应用级**配置、拿不到当前工程，传默认值（null）时只展示目录约定与说明
 */
class ConfigPanel(
    private val showModuleChooser: Boolean,
    private val preview: JComponent? = null,
    private val basePath: () -> String? = { null }
) {

    private val moduleCombo = ComboBox<Module>()
    private val basePackageField = JBTextField()
    private val tablePrefixField = JBTextField()
    private val entitySuffixField = JBTextField()
    private val authorField = JBTextField()

    private val sqlKeywordsEditor = TagEditor()
    private val overwriteMethods = JBCheckBox("同签名方法实现有差异时覆盖（关闭则只补缺不覆盖）", true)
    private val previewBeforeWrite = JBCheckBox("写入前显示变更预览（可逐文件 / 逐模板取消）", true)
    private val markGenerated = JBCheckBox("新建的 Java 产物加 @Generated 来源标记", true)
    private val useLombok = JBCheckBox("Lombok：实体 / DTO / VO / QueryDTO 用类级 @Getter / @Setter 代替手写 getter / setter", true)
    private val ignoreFieldsField = JBTextField()
    private val nonFuzzyFieldsField = JBTextField()
    private val searchStringField = JBTextField()
    private val replaceStringField = JBTextField()
    private val databaseNameField = JBTextField()

    private val dateKindCombo = choiceCombo(
        Choice(DateKind.LOCAL_DATE_TIME.name, "java.time（LocalDateTime / LocalDate）"),
        Choice(DateKind.JAVA_UTIL_DATE.name, "java.util.Date")
    )
    private val dialectCombo = choiceCombo(
        Choice(SqlDialect.MYSQL.name, "MySQL / MariaDB"),
        Choice(SqlDialect.POSTGRESQL.name, "PostgreSQL"),
        Choice(SqlDialect.ORACLE.name, "Oracle")
    )
    /**
     * 模板方案（套）：选项来自模板仓库——内置 MyBatis / MyBatis-Plus 两套 + 用户自建套 + 项目模板套。
     * 选定方案即选定目标框架，不再单独维护「目标框架」下拉。
     */
    private val templateSetCombo = ComboBox<Choice>()

    /** 项目模板的只读状态：只告诉用户「有没有、几个、在哪」，编辑直接改工程里的 .tpl */
    private val projectHint = JBLabel()

    /** 打开项目模板目录（不存在就建出来，省得用户自己拼路径） */
    private val openProjectDirLink = LinkLabel<Any?>("打开目录", null) { _, _ -> openProjectTemplatesDir() }
    private val injectionCombo = choiceCombo(
        Choice("JAKARTA_RESOURCE", "@Resource（jakarta.annotation）"),
        Choice("JAVAX_RESOURCE", "@Resource（javax.annotation）"),
        Choice("AUTOWIRED", "@Autowired")
    )

    val component: JComponent

    init {
        moduleCombo.renderer = SimpleListCellRenderer.create("") { module: Module? -> module?.name ?: "" }

        // ---------------------------------------------------------- 布局原语（纯 Swing 组合，无 Scrollable 副作用）

        /** 单行：标签在上、控件在下（中文标签较长，横向排布会被截断；行高 = 标签 + 30 + 6 间距） */
        fun row(label: String, field: JComponent): JComponent =
            JPanel(BorderLayout(0, 2)).apply {
                border = JBUI.Borders.emptyBottom(6)
                add(JBLabel(label), BorderLayout.NORTH)
                add(field, BorderLayout.CENTER)
            }

        /** 多列组：每组单元格 = 标签在上、控件在下 */
        fun grid(columns: Int, items: List<Pair<String, JComponent>>): JComponent =
            JPanel(GridLayout(0, columns, 10, 6)).apply {
                items.forEach { (label, field) -> add(row(label, field)) }
            }

        /** 区块标题 */
        fun section(title: String): JComponent = TitledSeparator(title)

        /** 纵向堆叠：严格按子组件 preferred 高度排列，宽度拉伸填满 */
        fun vertical(vararg comps: JComponent): JComponent =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                comps.forEach { add(it) }
            }

        /** Tab 内容包滚动条：NORTH 锚定贴顶 + 底部 16px，内容超出才滚动 */
        fun scrollable(content: JComponent): JComponent {
            val host = JPanel(BorderLayout()).apply {
                add(content, BorderLayout.NORTH)
                border = JBUI.Borders.emptyBottom(16)
            }
            return JBScrollPane(
                host,
                JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            ).apply { border = null }
        }

        // ---------------------------------------------------------- Tab 1：生成（每次都可能调整，约 420px，一屏放下）

        val generateContent = vertical(
            *(buildList {
                preview?.let { add(it) }
                if (showModuleChooser) {
                    add(row("目标模块：", moduleCombo))
                }
                add(row("基础包名：", basePackageField))
                add(row("库名（XML 路径 mybatis/数据库类型/库名）：", databaseNameField))
                add(section("模板方案与类型"))
                // 一行一项
                add(row("模板方案（整套模板）", templateSetCombo))
                add(projectTemplatesRow())
                add(row("日期类型", dateKindCombo))
                add(row("SQL 方言", dialectCombo))
            }.toTypedArray())
        )

        // ---------------------------------------------------------- Tab 2：代码风格（偶尔调整，约 500px）

        val styleContent = vertical(
            section("命名（影响预览与生成类名）"),
            grid(
                2,
                listOf(
                    "表前缀（逗号分隔，生成类名时去掉）" to tablePrefixField,
                    "实体类后缀" to entitySuffixField,
                    "作者" to authorField
                )
            ),
            // Search / Replace 配套使用，固定放同一行
            grid(
                2,
                listOf(
                    "Search String（表名替换，配合 Replace String 生成类名）" to searchStringField,
                    "Replace String（与 Search String 配套）" to replaceStringField
                )
            ),
            section("字段与注入"),
            // 一行一项（长标签 + 长取值，双列会挤在一起）
            row("Mapper 注入方式", injectionCombo),
            row("Lombok", useLombok),
            row("忽略字段（逗号分隔，不生成，例如审计字段）", ignoreFieldsField),
            row("非模糊查询列（逗号分隔，不生成 Like 条件）", nonFuzzyFieldsField),
            section("写入策略"),
            row("Java 合并", overwriteMethods),
            row("生成安全", previewBeforeWrite),
            row("产物标记", markGenerated),
            section("SQL"),
            row("SQL 保留字（非 MySQL 方言列名命中时加双引号；标签可增删，清空全部恢复内置默认）", sqlKeywordsEditor)
        )

        val tabs = JBTabbedPane()
        tabs.addTab("生成", scrollable(generateContent))
        tabs.addTab("代码风格", scrollable(styleContent))

        component = tabs
        // 固定标准尺寸 740 × 660：单列排布后内容变高，留出余量避免底部裁切；
        // 两个 Tab 的内容都小于视口，全程无滚动；窗口可手动拖拽
        component.preferredSize = Dimension(740, 660)
        component.minimumSize = Dimension(600, 420)

        // 单行输入框与下拉框统一首选高度，保证所有行等高
        val uniformHeight = JBUI.scale(30)
        listOf(
            basePackageField, tablePrefixField,
            entitySuffixField, authorField, searchStringField, replaceStringField,
            ignoreFieldsField, nonFuzzyFieldsField, databaseNameField
        ).forEach { field ->
            field.preferredSize = Dimension(field.preferredSize.width, uniformHeight)
        }
        listOf(
            moduleCombo, templateSetCombo, dateKindCombo, dialectCombo, injectionCombo
        ).forEach { combo ->
            combo.preferredSize = Dimension(combo.preferredSize.width, uniformHeight)
        }

        databaseNameField.toolTipText = "用于 Mapper XML 路径的库名层级（src/main/resources/mybatis/数据库类型/库名/xxxMapper.xml）；留空则省略该层级"
        overwriteMethods.toolTipText = "开启：二次生成时，同签名方法（方法名 + 参数类型）的实现与本次生成不一致时，用新实现整体替换旧方法（含文档）——适合跟随插件升级同步生成器演进。关闭：维持只补缺策略，用户手改过的方法不会被冲掉。用户自定义的新增方法（生成器不产出的）永远不受影响"
        useLombok.toolTipText = "开启（默认）：实体 / DTO / VO / QueryDTO 用类级 @Getter / @Setter（import lombok）代替手写访问器，要求工程已引入 lombok 依赖。关闭：照旧展开每个字段的 getter / setter。内置模板按 \$cfg.useLombok 分支，自建模板同样可以读这个变量"
        ignoreFieldsField.toolTipText = "列名或字段名均可，默认 create_by,update_by,create_at,update_at（参考插件 ignoreColumns）"
        nonFuzzyFieldsField.toolTipText = "列名或字段名均可，默认 create_by, update_by（参考插件 nonFuzzySearchColumn）"
        searchStringField.toolTipText = "表名替换规则：生成类名前把表名里的 Search String 替换为 Replace String（对应原插件 DomainObjectRenamingRule）"
        replaceStringField.toolTipText = "与 Search String 配套使用，Search String 为空时不生效"

    }

    fun resetFrom(config: GenerationConfig, modules: List<Module>) {
        if (showModuleChooser) {
            moduleCombo.removeAllItems()
            modules.forEach { moduleCombo.addItem(it) }
            val selected = modules.firstOrNull { it.name == config.moduleName } ?: modules.firstOrNull()
            if (selected != null) {
                moduleCombo.selectedItem = selected
            }
        }
        basePackageField.text = config.basePackage









        tablePrefixField.text = config.tablePrefixes
        entitySuffixField.text = config.entitySuffix
        authorField.text = config.author



        databaseNameField.text = config.databaseName






        sqlKeywordsEditor.setTags(config.sqlReservedKeywords.split(','))
        overwriteMethods.isSelected = config.overwriteMethods
        previewBeforeWrite.isSelected = config.previewBeforeWrite
        markGenerated.isSelected = config.markGenerated
        useLombok.isSelected = config.useLombok

        ignoreFieldsField.text = config.ignoreFields
        nonFuzzyFieldsField.text = config.nonFuzzySearchColumns

        searchStringField.text = config.searchString
        replaceStringField.text = config.replaceString


        currentFramework = config.frameworkEnum
        // 方案清单只算一次：resolveSet 与下拉填充用的是同一份（项目模板扫描虽缓存，也没必要跑两遍）
        val sets = TemplateRegistry.sets(basePath())
        refreshTemplateSets(TemplateRegistry.resolveSet(config, sets), sets)
        select(dateKindCombo, config.dateKindEnum.name)
        select(dialectCombo, config.dialectEnum.name)
        select(injectionCombo, config.injectionStyle)
    }

    /** 下拉里重建模板方案清单：内置 + 全局 + 当前项目的模板取并集 */
    private fun refreshTemplateSets(
        selected: String,
        sets: List<TemplateRegistry.TemplateSet> = TemplateRegistry.sets(basePath())
    ) {
        setFrameworks.clear()
        templateSetCombo.removeAllItems()
        sets.forEach { set ->
            setFrameworks[set.id] = set.framework
            val origin = if (set.source == TemplateRegistry.TemplateSet.SOURCE_PROJECT) " · 项目" else ""
            templateSetCombo.addItem(Choice(set.id, "${set.id}（${set.count} 个模板$origin）"))
        }
        select(templateSetCombo, selected)
        if (templateSetCombo.selectedIndex < 0 && templateSetCombo.itemCount > 0) {
            templateSetCombo.selectedIndex = 0
        }
        refreshProjectHint()
    }

    /** 项目模板只读提示行：一行统计 + 右侧「打开目录」 */
    private fun projectTemplatesRow(): JComponent = JPanel(BorderLayout(8, 0)).apply {
        border = JBUI.Borders.emptyBottom(6)
        add(projectHint, BorderLayout.CENTER)
        add(openProjectDirLink, BorderLayout.EAST)
    }

    /**
     * 刷新项目模板提示。
     *
     * 设置页是应用级配置、拿不到当前工程（[basePath] 为 null），那里只展示目录约定；
     * 生成弹窗里才有工程上下文，能报出真实数量并打开目录。
     */
    private fun refreshProjectHint() {
        val path = basePath()
        if (path == null) {
            projectHint.text = "项目模板：项目根的 ${ProjectTemplates.DIR}/<方案>/*.tpl（随工程提交，生成时与内置方案并列可选）"
            openProjectDirLink.isVisible = false
            openProjectDirLink.parent?.revalidate()
            return
        }
        openProjectDirLink.isVisible = true
        val scan = ProjectTemplates.scan(path)
        projectHint.text = if (scan.sets.isEmpty()) {
            "项目模板：未使用（可选：${ProjectTemplates.DIR}/<方案>/*.tpl）"
        } else {
            "项目模板：${scan.sets.size} 套 / ${scan.count} 个模板（${ProjectTemplates.DIR}）"
        }
        openProjectDirLink.parent?.revalidate()
    }

    /** 用系统文件管理器打开项目模板目录（平台该版本只有单参重载，双参版本在更新的 SDK 里） */
    @Suppress("DEPRECATION")
    private fun openProjectTemplatesDir() {
        val path = basePath() ?: return
        val dir = ProjectTemplates.dirOf(path)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        ShowFilePathAction.openFile(dir)
    }

    /** 当前选中的方案 id */
    private fun selectedSetId(): String =
        (templateSetCombo.selectedItem as? Choice)?.value.orEmpty()

    /**
     * 选中方案对应的目标框架：自定义套没声明框架时沿用上一次的选择。
     *
     * 框架值在 [refreshTemplateSets] 里就跟着下拉存好了——设置页的 isModified() 会被平台高频调用，
     * 这里再去问模板仓库（重读重解析全部模板）会明显卡顿。
     */
    private fun selectedFramework(): TargetFramework =
        setFrameworks[selectedSetId()] ?: currentFramework

    /** 方案 id → 该方案的框架（重建下拉时填充） */
    private val setFrameworks = mutableMapOf<String, TargetFramework?>()

    /** 上一次生效的目标框架（自定义套没声明框架时兜底，保证代码语义不会莫名回退） */
    private var currentFramework: TargetFramework = TargetFramework.MYBATIS

    fun applyTo(config: GenerationConfig) {
        if (showModuleChooser) {
            (moduleCombo.selectedItem as? Module)?.let { config.moduleName = it.name }
        }
        config.basePackage = basePackageField.text.trim()









        config.tablePrefixes = tablePrefixField.text.trim()
        config.entitySuffix = entitySuffixField.text.trim()
        config.author = authorField.text.trim()



        config.databaseName = databaseNameField.text.trim()






        config.sqlReservedKeywords = sqlKeywordsEditor.tags().joinToString(",")
        config.overwriteMethods = overwriteMethods.isSelected
        config.previewBeforeWrite = previewBeforeWrite.isSelected
        config.markGenerated = markGenerated.isSelected
        config.useLombok = useLombok.isSelected
        config.ignoreFields = ignoreFieldsField.text.trim()
        config.nonFuzzySearchColumns = nonFuzzyFieldsField.text.trim()

        config.searchString = searchStringField.text.trim()
        config.replaceString = replaceStringField.text.trim()


        // 方案决定框架：选中方案即选定目标框架（自定义套没声明框架时沿用上一次）
        config.templateSet = selectedSetId()
        config.frameworkEnum = selectedFramework()
        config.dateKind = (dateKindCombo.selectedItem as? Choice)?.value ?: DateKind.LOCAL_DATE_TIME.name
        config.dialect = (dialectCombo.selectedItem as? Choice)?.value ?: SqlDialect.MYSQL.name
        config.injectionStyle = (injectionCombo.selectedItem as? Choice)?.value ?: "JAKARTA_RESOURCE"
    }

    fun selectedModule(): Module? = moduleCombo.selectedItem as? Module

    /** 校验，返回错误信息 */
    fun validate(): String? {
        if (showModuleChooser && selectedModule() == null) {
            return "请选择目标模块"
        }
        val pkg = basePackageField.text.trim()
        if (pkg.isEmpty()) {
            return "基础包名不能为空"
        }
        if (!PKG_PATTERN.matches(pkg)) {
            return "基础包名不合法：$pkg"
        }
        val setId = selectedSetId()
        if (setId.isBlank()) {
            return "请选择模板方案"
        }
        if (TemplateRegistry.forSet(setId, basePath()).isEmpty()) {
            return "模板方案 $setId 里没有可用模板，请先在设置里添加模板或改用其他方案"
        }
        return null
    }

    fun tablePrefixComponent(): JComponent = tablePrefixField

    fun entitySuffixComponent(): JComponent = entitySuffixField

    private fun select(combo: ComboBox<Choice>, value: String) {
        val model = combo.model
        for (i in 0 until model.size) {
            if (model.getElementAt(i).value == value) {
                combo.selectedIndex = i
                return
            }
        }
    }

    private fun choiceCombo(vararg items: Choice): ComboBox<Choice> =
        ComboBox<Choice>().apply { items.forEach { addItem(it) } }

    companion object {
        private val PKG_PATTERN = Regex("^[a-zA-Z_$][a-zA-Z\\d_$]*(\\.[a-zA-Z_$][a-zA-Z\\d_$]*)*$")
    }
}
