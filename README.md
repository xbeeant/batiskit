# BatisKit

> 模板驱动的 IntelliJ IDEA 插件：从数据库表生成整套 Spring / MyBatis 分层代码，并把 Mapper 开发、日志排查、接口调试和日常编辑效率收进同一个 IDE。

插件展示名为 **BatisKit**；插件 ID 沿用 `org.xstudio.plugin.idea`，Marketplace 的升级链路与评分不受影响。

版本 `2.0.0` · IntelliJ IDEA `2024.1+`（build 241）· Kotlin / JVM 17 · 235 个 Kotlin 源文件 · 935 项自动化测试

- [它解决什么问题](#1-它解决什么问题)
- [环境要求与安装](#2-环境要求与安装)
- [三分钟上手 生成第一张表的代码](#3-三分钟上手-生成第一张表的代码)
- [两套内置模板方案](#4-两套内置模板方案)
- [模板系统 想改产物就看这里](#5-模板系统-想改产物就看这里)
- [功能地图](#6-功能地图)
- [快捷键](#7-快捷键)
- [设置与个性化](#8-设置与个性化)
- [写入是安全的](#9-写入是安全的)
- [架构与代码结构](#10-架构与代码结构)
- [开发与验证](#11-开发与验证)
- [已知边界](#12-已知边界)
- [常见问题](#13-常见问题)
- [项目资料](#14-项目资料)

---

## 1. 它解决什么问题

写一套 `t_user` 的 Entity / Mapper / XML / Service / Controller，通常是复制粘贴加全局替换；换项目时类名风格、基类、注解、分页方言又全都不一样。传统生成器把这些规则写死在代码里，想改一个 `@Data` 都要改插件源码重新打包。同时 Mapper 接口与 XML 靠肉眼对齐，控制台里的 MyBatis 日志要手工把 `?` 换成参数才能执行。

本插件的做法是：**生成什么、生成成什么样，全部由模板决定。**

| 设计原则 | 具体含义 |
| --- | --- |
| 模板是唯一真相 | 类名、包路径、import、基类、注解、方法体都是 `.tpl` 里的文本；插件只把「表结构 + 配置」摊平成模型交给模板。加一种产物 = 往方案文件夹里放一个 `.tpl`，不改插件代码 |
| 不重复连库 | 表结构直接读 IDE Database 视图的 PSI 元数据，不需要再填一次连接信息，不引入 JDBC、不读 `application.yml`、不保存账号密码 |
| 运行期零第三方依赖 | 渲染引擎、SQL 还原、JSON 解析、时序图分析全部自研；只依赖 IDE 平台自带能力 |
| 不打扰 | 每个能力都能在「模块开关」里整体启停，避免与本机已装的同类插件重复入口 |

插件功能远不止代码生成——[第 6 节](#6-功能地图) 是完整的能力清单。

---

## 2. 环境要求与安装

| 项目 | 要求 |
| --- | --- |
| IDE | IntelliJ IDEA **2024.1+**（`sinceBuild = 241`）。`com.intellij.database` 是强依赖，因此需要带 Database Tools 的 IDE（IDEA Ultimate）；Community 版不受支持 |
| 内置插件 | `com.intellij.java`、`com.intellij.database`、`Git4Idea` |
| 插件运行时 | IDE 自带的 JBR 17（2024.1 的运行环境）；字节码目标 JVM 17 |
| 构建环境 | Gradle 守护进程需 **JDK 21**；Kotlin 的 JDK 17 toolchain 由 `foojay-resolver` 自动解析 |
| 构建工具 | Gradle Wrapper（`./gradlew`）+ Kotlin 2.3.20 + IntelliJ Platform Gradle Plugin 2.x |

**安装**

1. 在 IDE 插件市场搜索 `BatisKit` 安装（若该版本已上架）；或按下面第 2 步从源码构建安装。
2. 本地构建：`./gradlew buildPlugin` → `build/distributions/*.zip` → *Settings → Plugins → Install Plugin from Disk*。
3. 装好后建议先去 *Settings → Tools → BatisKit* 看一眼「模块开关」，把用不上的能力关掉。

插件支持动态安装 / 更新：装新包后右下角出现 **Apply** 即生效。已打开工程的常驻监听（日志捕获、Git Toolbox）需要重开工程或重启 IDE 才会重新挂载。

---

## 3. 三分钟上手 生成第一张表的代码

```
Database 视图（选中一张或多张表）
   │ 右键「生成代码（BatisKit）」 / ⇧⌘G
   ▼
配置弹窗
   ├─「生成」     目标模块 / 基础包名 / 库名 / 模板方案 / 日期类型 / SQL 方言
   └─「代码风格」 命名 · 字段与注入 · 写入策略 · SQL
   （底部）待生成表清单 + 实体类名预览，可搜索、点列头排序
   │ 确定
   ▼
逐表建模型 → 遍历选中方案的每个 .tpl → 渲染 → 变更预览
   │ 确认
   ▼
新文件创建 / 已有文件按「缺什么补什么」合并 → 整批可一次 Ctrl+Z 撤销
```

以 `t_user(id, user_name, age, create_time)`、基础包 `com.example.demo`、`mybatis-pure` 方案为例：

```text
src/main/java/com/example/demo/
├── model/User.java                    实体（纯 POJO）
├── dto/UserQueryDTO.java              条件对象（含日期区间字段）
├── mapper/UserMapper.java             显式声明 12 个 CRUD 方法
├── service/IUserService.java          Service 接口
├── service/impl/UserServiceImpl.java  Service 实现
└── rest/UserRestController.java       原生 Spring REST 端点
src/main/resources/mybatis/mysql/<库名>/UserMapper.xml   全套 SQL
```

Mapper XML 的目录由 `mapperXmlDir` 推导为 `mybatis/<方言>/<库名>`，库名留空时省略该层。

三个常见误区：

- **包结构、基类、生成哪些文件都不在配置里**，它们由模板的 `output` 路径与正文决定。
- **选了模板方案就等于选了框架**（`mybatis*` → MyBatis，`mybatis-plus*` → MyBatis-Plus），表单里没有单独的「目标框架」下拉。
- 配置是 IDE 全局的（`options/batiskit-generator.xml`），任意工程右键都会带出上次的选择。

---

## 4. 两套内置模板方案

`src/main/resources/templates/` 下每个文件夹就是一套方案，生成弹窗的「模板方案」下拉里直接切换。

| 方案 | 模板数 | 外部依赖 | 适合 |
| --- | --- | --- | --- |
| `mybatis-pure` | 7 | 仅 MyBatis + Spring | **没有父类可继承**的干净工程：实体纯 POJO，Mapper 接口与 XML 显式写全 CRUD |
| `mybatis-plus-pure` | 6 | MyBatis-Plus `BaseMapper` | 干净工程 + MP：Mapper 只继承 `BaseMapper`，ServiceImpl 用 `QueryWrapper` 拼条件，不需要 XML |

使用前提：MP 方案的 `selectPage` 需要工程注册 `PaginationInnerInterceptor`；纯 MyBatis 方案由模板按方言生成 `LIMIT … OFFSET …`（Oracle 为 `OFFSET … FETCH …`）。Controller 刻意不暴露「按条件更新」端点（两个入参无法共用一个 `@RequestBody`），需要时直接调 Service 的 `updateByCondition`。

---

## 5. 模板系统 想改产物就看这里

### 5.1 目录即方案，三层来源取并集

| 来源 | 位置 | 作用范围 | 能做什么 |
| --- | --- | --- | --- |
| 内置 | 插件资源 `resources/templates/<方案>/` | 所有工程 | 打底的两套方案 |
| 全局 | IDE 设置 `batiskit-templates.xml` | 本机所有工程 | 与内置模板同 `id` 时覆盖内容；写 `set:` 可自建方案 |
| 项目 | 工程内 `.batiskit/templates/<方案>/` | 当前工程 | 只能**新增**方案，随代码进版本库、团队共享 |

自建项目模板没有任何注册表：

```text
<项目根>/.batiskit/templates/
└── acme/                ← 下拉里显示「acme（3 个模板 · 项目）」
    ├── entity.tpl
    ├── mapper.tpl
    └── dto.tpl
```

项目模板改完即生效（扫描按文件名 + 大小 + mtime 判缓存），同名于内置 / 全局方案时整目录跳过并在结果里说明原因。整套方案还能用模板工作台导出成一个 `*.templateset.xml` 发给同事，导入后成为自包含的新方案，头部注释、字段顺序与正文逐字保留。

### 5.2 一个模板文件长什么样

```text
---
id: entity
name: 实体类
group: 基础
order: 10
output: {basePackageDir}/model/{entity}.java
root: java
merge: java
framework: mybatis
---
package {package};

#foreach($fqn in $util.imports($tableColumns, "java.io.Serializable"))
import ${fqn};
#end

$util.javaDoc($table.comment, "实体类")
public class ${entity} implements Serializable {
#foreach($c in $tableColumns)
    private ${c.type} ${c.field};
#end
}
```

头部是 `---` 包围的 `key: value`，**值本身也是模板串**（所以 `output` 能写 `{basePackageDir}`）：

| 字段 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `id` | 是 | — | 套内唯一；用户模板与内置同 `id` 时覆盖内置 |
| `output` | 是 | — | 相对路径；路径里的目录就是包 |
| `name` / `group` / `order` | 否 | `id` / `自定义` / `100` | 弹窗里的显示与排序 |
| `root` | 否 | `java` | `java` 写源码根，`resources` 写资源根 |
| `merge` | 否 | 按 `root` 推断 | `java` 按签名补缺 / `xml` 按 id 补缺 / `overwrite` 直接覆盖 |
| `enabled` | 否 | `true` | 唯一的产物开关，值也是模板串 |
| `set` | 否 | 空 | 所属方案；内置模板不用写（文件夹名即方案） |
| `framework` | 否 | `any` | `any` / `mybatis` / `mybatis-plus` |

头部写错（未知字段、缺 `id` / `output`、非法枚举值）会在生成结果里给出具体行号，不会静默跳过。

### 5.3 语法速查

自研引擎是 Velocity 语法的子集，零依赖，解析与渲染全程不碰 PSI，因此模板产物能用普通 JUnit 断言。

| 写法 | 例子 |
| --- | --- |
| 变量 / 属性 / 下标 | `$entity`、`$table.namingBase`、`$columns[0]` |
| 紧贴文字 | `${entity}DTO`；`{Model}` 是等价的简写（编译前降级成 `${...}`） |
| 方法 | `${entity.uncap()}`、`$columns.size()`、`$columns.join(", ")` |
| 工具函数 | `$util.imports(...)`、`$util.pagination()`、`$util.javaDoc(...)` |
| 条件 / 循环 / 赋值 | `#if($singlePk) … #else … #end`、`#foreach($c in $columns) … #end`、`#set($x = "…")` |
| 注释 / 原样块 / 转义 | `## 行注释`、`#* 块注释 *#`、`#[[ … ]]#`、`\$` `\#` `\{` |

要点：双引号字符串支持 `$` 插值，单引号是纯文本；未定义变量输出空串并记入告警；**独占一行的指令会吃掉行首缩进与行尾换行**，指令前后的空行要自己控制。模型里没有的 `@DeleteMapping("/{id}")`、`#{id,jdbcType=BIGINT}` 这类大括号原样保留。

### 5.4 模板里能拿到什么

模型是纯 `Map / List / String / Boolean / Number`，不使用反射。

| 类别 | 常用键 |
| --- | --- |
| 命名 | `table.name` / `table.comment` / `table.namingBase` / `entity` / `keyType` / `package` / `basePackageDir` / `mapperXmlDir` / `controllerPath` |
| 列清单 | `tableColumns`（表的全部实际列）、`columns`（实体列，剔除忽略字段）、`queryColumns`、`insertColumns`、`updatableColumns`、`pkColumns`、`singlePk` |
| 列 Map 字段 | `column` / `field` / `cap` / `type` / `typeFqn` / `jdbcType` / `comment` / `dbType` / `pk` / `auto` / `nullable` / `dateLike` / `sqlName` |
| 结构开关 | `hasPk` / `hasBatch` / `hasVersion` / `hasBlobColumns` / `isMybatisPlus` / `generateCrud` |
| 配置白名单 | `cfg.basePackage` / `cfg.tablePrefixes` / `cfg.entitySuffix` / `cfg.serviceInterfacePrefix` / `cfg.author` / `cfg.injectionStyle` / `cfg.dateKind` / `cfg.dialect` / `cfg.ignoreFields` |
| 排版块 | `{fields}` / `{accessors}` / `{mapperMethods}` / `{xmlStatements}`（共用片段在 `resources/blocks/`） |
| 工具函数 | `$util.imports` / `simplify` / `javaDoc` / `implClause` / `accessors` / `param` / `columnNames` / `columnParams` / `sqlColumn` / `like` / `pagination` / `pkParams` / `pkWhere` / `pairs` 等 |

`util.*` 承担所有推导（import 收集、方言列名与分页、主键参数、JavaDoc），模板只负责排版。模板工作台提供 `.tpl` 语法高亮、模型键与指令补全、废弃键提示，以及用示例表实时预览渲染结果。

---

## 6. 功能地图

### 代码生成与模板

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| 生成代码 | Database 视图右键 / ⇧⌘G | 多表批量生成，两套 Tab 配置，表清单可搜索排序 |
| 生成前预览 | 点击「确定」后 | 后台渲染可取消；按新建 / 合并 / 覆盖 / 无变化分组，逐文件勾选、按模板整组排除、看行级 diff |
| 模板工作台 | Tools → BatisKit 模板工作台 | 编辑与实时预览模板，整套方案导入 / 导出为 XML |
| 表结构比对 | Database 视图右键 | 只读漂移检测：缺字段 / 类型不一致 / 多出字段 / 产物未生成；按模板同步 |
| JSON → Java 对象 | 编辑器或 Project View 右键 / ⇧⌘J | 多份 JSON 取字段并集，冲突类型智能收敛，四种注解库可叠加，字段表可编辑 + 实时代码预览 |

### Mapper 开发

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| 接口 ↔ XML 导航 | 侧栏图标 / Ctrl+B | 双向跳转；Alt+Enter 补 `@Param`、按前缀生成带表名的 CRUD 语句 |
| 重命名与 Find Usages | Shift+F6 / ⌥F7 | 语句 id ↔ 方法、`resultMap` / `<sql>` 片段、`<include refid>` 联动改写；接口改名时 namespace 与 XML 文件名跟随 |
| 静态检查 | Settings → Editor → Inspections → MyBatis | 参数绑定、语句与接口互查、`${}` 注入风险、返回类型匹配，均带 Alt+Enter 修复 |
| 字段补全与 CRUD 预填 | XML 编辑器 | 列来自 IDE 数据源 / 离线快照 / 实体类 / resultMap 四路合并，首次使用无需先选表；`#{` 上下文参数补全 |
| Mapper XML 格式化 | Ctrl+Alt+L / 右键 | 「XML 层级打底 + SQL 相对层次叠加」，只动空白不改字符，支持选区与保存时格式化 |
| Code Vision | Mapper 方法上方 | 显示对应 XML 语句行数，点击跳转 |

### 日志与控制台

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| MyBatis Log | 底部工具窗口 / 控制台右键 | 捕获 Run / Debug 日志，把 `?` 还原成可执行 SQL；着色预览、搜索、四种排序、格式化 / 复制 / EXPLAIN、按文本相似度跳回 Mapper XML 语句；另有文本还原工作台 |
| Grep Console | Run / Debug 控制台 / 底部工具窗口 | 按级别着色、关键词片段高亮、规则链过滤（默认关闭）、Grep 子控制台链式过滤、一键只看 ERROR |

### 接口与调用链

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| Spring API | 右侧工具窗口 | 扫描 `@RestController` / `@Controller` / `@Scheduled` / Dubbo，复制完整 URL 或 cURL；自动探测 context path，源码变化自动重扫 |
| 接口调试 | Spring API 列表右键「发送请求…」 | IDE 内编辑参数并发一次请求，查看响应，或「另存为 .http」交给原生 HTTP Client |
| 时序图 | 方法内右键 / ⌥S / Spring API 面板 | Java PSI + Kotlin UAST 双路径生成调用链，MyBatis 工程可继续下钻到 SQL 与数据库表；导出 SVG / PNG / PlantUML / Mermaid，双击箭头跳声明 |

### 编辑器效率

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| String Manipulation | 编辑器右键 / Alt+Enter / 右侧工具窗口 | 40 项转换：大小写、驼峰与下划线、URL / Base64、排序、去重、拼接；模板里可用 `$str.*` |
| GenerateAllSetter | 编辑器右键 / Alt+Enter | 一键生成全部 setter 并按类型填默认值，支持继承、链式 setter、Lombok |
| Rainbow Brackets | 编辑器右键 / Color Scheme | `()` `[]` `{}` 与 XML 标签按层级着色，最多 10 色，可高亮当前括号作用域 |

### 工程协作

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| Git Toolbox | 状态栏 / Project View / 右键 | 分支状态、行内 blame、自动 fetch、远程链接、提交信息校验、分支清理 |
| 设置备份与迁移 | Settings → Tools → BatisKit 父页 | 十组偏好整组导出 / 导入 / 恢复默认，导入禁用 DOCTYPE 与外部实体 |
| 模块开关 | 同名子页 | 按 10 个模块整体启停，避免与本机同类插件重复入口 |

---

## 7. 快捷键

带 `⌘` 的是 macOS 写法，Windows / Linux 用 `Ctrl` 代替；最后两项两边共用。

| 动作 | macOS | Windows / Linux |
| --- | --- | --- |
| 生成代码 | ⇧⌘G | Shift+Ctrl+G |
| JSON → Java 对象 | ⇧⌘J | Shift+Ctrl+J |
| 复制远程仓库链接 | ⇧⌘⌥C | Shift+Ctrl+Alt+C |
| Grep 过滤控制台 | ⌘⌥⇧G | Ctrl+Alt+Shift+G |
| 生成时序图 | ⌥S | Alt+S |
| 搜索全部字符串转换 | ⇧⌥M | Shift+Alt+M |

不想动鼠标时按 **Alt+Enter**：编辑器右键「BatisKit」子菜单里的动作都在意图列表中，文案、可用条件与模块开关状态与右键同源，子菜单也支持速度搜索。

---

## 8. 设置与个性化

全部集中在 **Settings → Tools → BatisKit**，十个子页都能在 Settings 搜索框里直接搜到：

`Generator` · `JSON → Java 对象` · `Rainbow Brackets` · `Git Toolbox` · `MyBatis Log` · `Grep Console` · `Mapper XML 格式化` · `Mapper 辅助` · `模块开关` · `时序图`

- 父页的「导出设置… / 导入设置… / 恢复默认设置…」把十组偏好打包成一个 XML；用户模板、项目模板与最近分支缓存不在这条通道里。
- 括号配色属于平台配色方案，在 *Editor → Color Scheme → Rainbow Brackets* 调整。
- 时序图页可编辑「跳过的基础类 / 工具类」名单（包前缀 / 简单类名 / 例外），不再写死在代码里。
- Grep Console 两张规则表支持搜索与列头排序，但搜索只影响显示，规则执行顺序仍是声明顺序。

生成器的主要配置项（`GenerationConfig`，IDE 全局持久化）：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| 基础包名 / 目标模块 | `io.github.xbeeant` / 当前模块 | 基础包名决定 `{basePackageDir}` |
| 模板方案 | `mybatis` | 选方案即选框架 |
| 表前缀 / 实体类后缀 | `t_` / 空 | 类名推导 |
| Service 接口名前缀 | `I` | 生成 `IUserService` |
| 忽略字段 | 一组审计字段 | 只作用于实体列清单，XML 仍按实际列生成 |
| 注入方式 / 日期类型 / SQL 方言 | `@Resource` / `LocalDateTime` / 自动识别 | 方言影响分页与列名引用 |
| 写入策略 | 开 | 同签名方法实现有差异时是否覆盖，见第 9 节 |

> 没有「代码风格」类开关：lombok、swagger、`serialVersionUID`、`@Mapper`、批量方法、日期区间、基类——全部写在模板正文里。

---

## 9. 写入是安全的

生成不是直接覆盖磁盘：

1. 后台渲染（带进度、可取消）→ 变更预览按**新建 / 合并 / 覆盖 / 无变化**分组，可逐文件勾选、查看行级 diff；产物清单支持搜索、列头排序与结果计数。
2. 确认后整批写入，**一次 Ctrl+Z 撤销**；新建产物带来源标记。
3. 二次生成按「缺什么补什么」合并：
   - **Java**：用平台 PSI 补缺的 import / 注解 / 字段 / 方法（按方法名 + 参数类型列表判定）；用户新增的方法永远不受影响。同签名方法实现不同时，由「写入策略」决定替换或保留。
   - **XML**：按 `id` 判断语句 / `resultMap` / `<sql>` 是否已存在，缺的才追加；合并后只格式化真正动过的标签。
   - 没有新增时记为「无变化」直接跳过。
4. 「比对已生成代码」是只读漂移检测，确认同步后仍走同一套预览与可撤销写入。

---

## 10. 架构与代码结构

```text
Database 视图（DbTable / DasColumn PSI）
        │ TableInfoReader
        ▼
TableInfo（表 / 列 / 主键 / 类型）
        │ TemplateModel
        ▼
纯 Map 模型 ── TemplateEngine（自研，Velocity 子集）── 文本
                                                    │
                                        CodeWriter（唯一接触平台 PSI 的一层）
                                                    │
                              JavaMerger / XmlMerger → 磁盘
```

渲染全程不碰 PSI，所以模板产物可以用普通 JUnit 断言；最外层才由 `CodeWriter` 负责创建与合并。

```text
src/main/kotlin/io/github/xbeeant/batiskit/
├── action/         Database 视图右键入口（生成、表结构比对）
├── meta/           表结构元数据、数据源定位、列缓存、SQL 语义与规则
├── model/          TableInfo / ColumnInfo / JavaType
├── generate/       GenContext（方言 / 路径 / 主键）、MapperModel、CodeGenerator
├── template/       引擎、占位符、排版块、方案仓库、模型、IDE 工作台与持久化
├── write/          CodeWriter、源码根解析、Java / XML 合并
├── completion/     XML 字段与参数补全
├── inspect/        Mapper 静态检查与 quickfix
├── navigation/     接口 ↔ XML 导航、重构引用、Code Vision
├── intention/      右键动作 → Alt+Enter 的桥接
├── api/            Spring / Dubbo 索引、请求调试、工具窗口
├── log/            MyBatis 日志解析、SQL 还原与着色、工具窗口
├── console/        控制台着色 / 过滤 / Grep 子控制台
├── sequence/       时序图：Java PSI + UAST 分析、布局、Swing / SVG / 文本导出
├── string/         40 项字符串操作与工具窗口
├── setter/         GenerateAllSetter 默认值推导与写回
├── rainbow/        彩虹括号层级、作用域、配色与设置
├── json/           JSON → Java：解析、类型推断、模板渲染
├── git/            Git Toolbox
├── ui/             配置面板、生成弹窗、设计系统 UiTokens
└── settings/       十组 Configurable
```

资源侧：`resources/templates/<方案>/*.tpl` 是内置方案，`resources/blocks/*.tpl` 是共用排版块，`resources/META-INF/plugin.xml` 是插件描述的唯一来源。

---

## 11. 开发与验证

```bash
./gradlew runIde            # 起一个带插件的沙箱 IDE
./gradlew test              # 全套测试（当前 935 项）
./gradlew buildPlugin       # 产出 build/distributions/*.zip
./gradlew verifyPlugin      # Plugin Verifier：2024.1 / 2025.3 / 2026.2 三端兼容性
./gradlew verifyPluginStructure
```

- **JDK 版本**：Gradle 9 / IntelliJ Platform Plugin 2.x 要求 JDK 21 启动守护进程；`jvmToolchain(17)` 由 `foojay-resolver` 自动解析。编译平台固定 `intellijIdea("2024.1")`，`sinceBuild = 241`，`untilBuild` 刻意不设（由 Plugin Verifier 实际把关）。想下调兼容下限，必须把编译平台、toolchain、`sinceBuild` 三者一起调整并重新编译。
- **热重载**：`runIde.autoReload = true`，重新执行构建任务后沙箱 IDE 自动重载插件；增删扩展点、改 `sinceBuild`、加依赖插件这类结构性改动仍需重启沙箱。
- **测试分四类**：纯逻辑（模板引擎、SQL 语义、类型推断、配色、搜索表达式）、真实平台 PSI（补全 / 检查 / 重构 / 合并）、Swing 离屏渲染（工具窗口密度与配色）、模板产物验证（两套方案的 Java 产物经语法解析，断言方法齐全、无内部父类、无未渲染占位符）。
- **CI**：`.github/workflows/ci.yml` 在 push / PR 上跑配置校验 → 测试 → 打包 → 结构校验；push 另跑 Plugin Verifier；打 `v*` tag 时用仓库 secret 签名并发布到 Marketplace。Marketplace 的 change-notes 只从 [CHANGELOG.md](CHANGELOG.md) 的 Unreleased 抽要点，不会把整份日志塞进插件描述。
- **签名与发布凭据**只从环境变量读取（`CERTIFICATE_CHAIN` / `PRIVATE_KEY` / `PRIVATE_KEY_PASSWORD` / `PUBLISH_TOKEN`），仓库里不落任何凭据。

本机构建若遇到 `gradlew` 没有执行权限，或 `java` 垫片指向错误版本，直接指定 JDK 21 并用 `bash` 执行即可：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS；其它系统指向 JDK 21 安装目录
bash gradlew test
```

界面改动请先读 [.impeccable.md](.impeccable.md)（本项目 UI 设计准则：间距与颜色统一由 `ui/UiTokens` 提供）。

---

## 12. 已知边界

- 只处理表 / 视图，不生成存储过程与外键关联（一对一 / 一对多）；复合主键下「按主键批量删除」不生成。
- 合并是「补缺 + 同签名方法覆盖开关」：表里删了列，已生成代码不会自动删字段；开启覆盖后手改过的同签名方法会被冲掉。
- MyBatis-Plus 方案不自动加 `@TableLogic`（逻辑删除由工程全局配置决定），`@Version` 需运行期注册乐观锁拦截器。
- 参数绑定检查不校验单参数 POJO 的属性名，也不校验 `<sql>` 片段内部的 `#{}`；跨 namespace 的 `include refid` 只解析本工程索引内的 XML。
- 时序图只展开静态可解析调用：反射、动态代理、协程与高阶函数的运行时分派看不到；图形是静态快照，不随编辑自动刷新。
- 从 MyBatis 日志反查 XML 语句基于**文本相似度**：动态标签展开后的 SQL、高度相似的多个语句会并列候选；注解式 SQL 与依赖 jar 里的 XML 不参与匹配。
- 控制台过滤默认关闭且**不可逆**（丢弃的行不会回来）；日常排查建议用底部工具窗口筛选或 Grep 子控制台。
- 设置导入导出只覆盖十组偏好；用户模板 / 项目模板走模板工作台，导入后已打开的设置页需重新进入才能看到新值。
- 模块开关关闭后右键入口与工具窗口立即隐藏，但已在运行的控制台过滤器 / 启动活动要重开工程才完全释放；Spring API 工具窗口不参与模块开关。
- 界面文案除 MyBatis Log 模块有中英双份语言包外，其余工具窗口与设置页仍是中文。

---

## 13. 常见问题

**生成的代码编译不过，报找不到 `BaseModelObject` 之类的类？**
选了 `mybatis` / `mybatis-plus` 方案，它们继承内部框架的基类。改用 `mybatis-pure` 或 `mybatis-plus-pure`。

**Database 视图里没有「生成代码」？**
需要带 Database Tools 的 IDE 并已配置数据源；`com.intellij.database` 是强依赖，Community 版不受支持。

**改了模板没生效？**
项目模板按「文件名 + 大小 + mtime」自动重扫；全局模板在设置页 / 模板工作台改完点 Apply。头部字段写错会在生成结果里给出行号。

**和 MybatisX、Grep Console 等插件入口重复？**
*Settings → Tools → BatisKit → 模块开关* 里整体关掉对应模块；Mapper 导航图标还有独立总开关。

**分页查不出来？**
MyBatis-Plus 方案需要注册 `PaginationInnerInterceptor`；纯 MyBatis 方案按方言生成分页片段，确认生成弹窗里的 SQL 方言与库一致。

---

## 14. 项目资料

| 文件 | 用途 |
| --- | --- |
| [CHANGELOG.md](CHANGELOG.md) | 完整变更记录；Marketplace 的 change-notes 从这里抽取 |
| [src/main/resources/META-INF/plugin.xml](src/main/resources/META-INF/plugin.xml) | 插件能力清单与唯一描述来源 |
| [src/main/resources/templates/](src/main/resources/templates/) | 两套内置模板方案，改产物从这里开始 |
| [build.gradle.kts](build.gradle.kts) / [settings.gradle.kts](settings.gradle.kts) | 构建、兼容范围、签名与发布配置 |
| [.github/workflows/ci.yml](.github/workflows/ci.yml) | 测试、打包、兼容校验与发布流水线 |
| [.impeccable.md](.impeccable.md) | 界面设计准则 |

维护者：Beeant · 组织 `io.github.xbeeant` · 插件 ID `org.xstudio.plugin.idea`
