package io.github.xbeeant.batiskit.api

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全工程的 Spring / Dubbo 接口索引（Tool Window 的数据源）。
 *
 * - 注解搜索走 [AnnotatedElementsSearch]，只扫源码（project scope）；
 * - 扫描在后台线程 + 读锁里完成，EDT 只读缓存；
 * - 每条记录带 [SmartPsiElementPointer]，双击可跳转到对应方法/字段且不会因 PSI 重建而失效。
 */
@Service(Service.Level.PROJECT)
class SpringApiService(private val project: Project) : Disposable {

    /** 列表项：接口信息 + 可导航的 PSI 指针（方法或字段） */
    data class Record(val endpoint: ApiEndpoint, val pointer: SmartPsiElementPointer<PsiElement>)

    @Volatile
    private var cache: List<Record> = emptyList()

    private var scanning: CancellablePromise<List<Record>?>? = null

    /** 每次启动扫描都递增；旧任务即使没被及时取消，也不能把过期结果写回缓存 */
    private val scanGeneration = AtomicInteger()

    private val listeners = CopyOnWriteArrayList<(List<Record>) -> Unit>()

    /** PSI 变化很多，合并成一次重扫；延迟避开正在批量构建索引的阶段 */
    private val invalidationAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    /** 只记录改动过的源文件路径；后台据此判断这次编辑是否碰过接口注解 */
    private val dirtyPaths = ConcurrentHashMap.newKeySet<String>()
    private val annotationByPath = ConcurrentHashMap<String, Boolean>()

    init {
        // 改了 @GetMapping / 新增 Controller 后列表会自动刷新，不必手动点「重新加载」。
        // 只监听 Java / Kotlin 源码，防抖后重扫；回调里只做调度，不做 PSI 访问。
        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            override fun childrenChanged(event: PsiTreeChangeEvent) {
                val file: PsiFile = event.file
                    ?: event.newChild?.containingFile
                    ?: event.child?.containingFile
                    ?: return
                val name = file.name
                if (!name.endsWith(".java", true) && !name.endsWith(".kt", true)) return
                val path = file.virtualFile?.path ?: return
                dirtyPaths.add(path)
                invalidate()
            }
        }, this)
    }

    fun records(): List<Record> = cache

    /** 订阅列表刷新；重复订阅同一个 listener 不会累积 */
    fun subscribe(onReady: (List<Record>) -> Unit) {
        listeners.remove(onReady)
        listeners.add(onReady)
        refresh(onReady)
    }

    fun unsubscribe(onReady: (List<Record>) -> Unit) {
        listeners.remove(onReady)
    }

    fun invalidate() {
        invalidationAlarm.cancelAllRequests()
        invalidationAlarm.addRequest({ refreshAll() }, REFRESH_DELAY_MS)
    }

    private fun refreshAll() {
        if (project.isDisposed) return
        val changed = dirtyPaths.toList().toSet()
        dirtyPaths.clear()
        refresh({ records -> listeners.forEach { listener -> runCatching { listener(records) } } }, changed)
    }

    /** 手动重新加载：无视「注解相关性」判断，全量重扫 */
    fun refresh(onReady: (List<Record>) -> Unit) {
        dirtyPaths.clear()
        refresh(onReady, emptySet())
    }

    /**
     * 后台重新扫描；[onReady] 在 EDT 回调（可能拿到空列表）。
     *
     * 用非阻塞读动作替代长 [ReadAction]：前者会把读锁切给写操作，避免大工程里按住 IDE；
     * [changedPaths] 非空时先做一次轻量的注解相关性判断，普通业务代码改动不再触发全工程扫描。
     */
    private fun refresh(onReady: (List<Record>) -> Unit, changedPaths: Set<String>) {
        val dumbService = DumbService.getInstance(project)
        if (dumbService.isDumb) {
            dumbService.smartInvokeLater {
                if (!project.isDisposed) {
                    refresh(onReady, changedPaths)
                }
            }
            return
        }

        val generation = scanGeneration.incrementAndGet()
        scanning?.cancel()
        scanning = ReadAction.nonBlocking(Callable<List<Record>?> {
            try {
                if (changedPaths.isNotEmpty() && !hasRelevantChange(changedPaths)) {
                    null
                } else {
                    scanProject()
                }
            } catch (e: IndexNotReadyException) {
                // 索引正在重建：晚点重来，别把空结果写进缓存
                dumbService.smartInvokeLater {
                    if (!project.isDisposed) refresh(onReady, changedPaths)
                }
                null
            } catch (e: ProcessCanceledException) {
                // 取消是正常流程，别记成扫描失败
                throw e
            } catch (e: Throwable) {
                LOG.warn("Spring API 扫描失败", e)
                emptyList()
            }
        })
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                if (generation != scanGeneration.get() || result == null || project.isDisposed) return@finishOnUiThread
                cache = result
                onReady(result)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * 判断这批文件里是否有改动前后与接口注解相关的文件。
     * 同一文件里继续编辑注解或路径仍然要重扫；首次看到的文件按「相关」处理，避免漏掉新增接口。
     */
    private fun hasRelevantChange(paths: Set<String>): Boolean {
        val manager = PsiManager.getInstance(project)
        var relevant = false
        for (path in paths) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(path)
            val file = virtualFile?.let { manager.findFile(it) }
            val nowAnnotated = if (file == null) {
                // 文件被删除 / 不在当前 VFS 里：保守起见重扫一次
                true
            } else {
                val text = file.text
                ANNOTATION_TOKENS.any { text.contains(it) }
            }
            val before = annotationByPath.put(path, nowAnnotated)
            if (before == null || before != nowAnnotated || nowAnnotated) {
                relevant = true
            }
        }
        return relevant
    }

    override fun dispose() {
        scanGeneration.incrementAndGet()
        scanning?.cancel()
        scanning = null
        invalidationAlarm.cancelAllRequests()
        listeners.clear()
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * 解析注解类用的 scope。
     *
     * 注解类（`org.springframework.web.bind.annotation.*`）几乎都在依赖 jar 里，
     * 而 [GlobalSearchScope.projectScope] 的 `isSearchInLibraries()` 为 false，
     * 用它 `findClass` 必然返回 null —— 表现就是「找不到任何 Spring 接口」。
     */
    internal fun annotationScope(): GlobalSearchScope = GlobalSearchScope.allScope(project)

    /** 扫描结果用的 scope：只要项目源码，库里的 `@Controller` 不算本项目的接口 */
    internal fun resultScope(): GlobalSearchScope = GlobalSearchScope.projectScope(project)

    /** 同步扫描（调用方需持有读锁；单测直接用） */
    internal fun scanProject(): List<Record> {
        val resolveScope = annotationScope()
        val searchScope = resultScope()
        val facade = JavaPsiFacade.getInstance(project)
        val pointers = SmartPointerManager.getInstance(project)
        val out = mutableListOf<Record>()
        // 非阻塞读动作会周期性让出读锁；这里显式检查取消，用户快速改选项时能及时停下
        fun checkCanceled() = ProgressIndicatorProvider.checkCanceled()
        checkCanceled()

        val controllers = LinkedHashSet<PsiClass>()
        SpringMappings.CLASS_ANNOTATIONS.forEach { fqn ->
            checkCanceled()
            val annotation = facade.findClass(fqn, resolveScope) ?: return@forEach
            controllers += AnnotatedElementsSearch.searchPsiClasses(annotation, searchScope).findAll()
        }
        controllers.forEach { controller ->
            checkCanceled()
            val basePaths = classPathPrefixes(controller)
            annotatedMethods(controller).forEach { method ->
                endpointsOf(controller, method, basePaths).forEach { endpoint ->
                    out += Record(endpoint, pointers.createSmartPsiElementPointer(method))
                }
            }
        }

        checkCanceled()
        val scheduled = facade.findClass(SpringMappings.SCHEDULED, resolveScope)
        if (scheduled != null) {
            AnnotatedElementsSearch.searchPsiMethods(scheduled, searchScope).findAll().forEach { method ->
                scheduledEndpoint(method)?.let { endpoint ->
                    out += Record(endpoint, pointers.createSmartPsiElementPointer(method))
                }
            }
        }

        // Dubbo 提供者：@DubboService 实现类里的每个公开方法
        DubboMappings.SERVICE_ANNOTATIONS.forEach { fqn ->
            checkCanceled()
            val annotation = facade.findClass(fqn, resolveScope) ?: return@forEach
            AnnotatedElementsSearch.searchPsiClasses(annotation, searchScope).findAll().forEach { psiClass ->
                dubboServiceEndpoints(psiClass).forEach { (endpoint, method) ->
                    out += Record(endpoint, pointers.createSmartPsiElementPointer(method))
                }
            }
        }

        // Dubbo 消费者：@DubboReference 字段（注入的远程服务引用）
        DubboMappings.REFERENCE_ANNOTATIONS.forEach { fqn ->
            checkCanceled()
            val annotation = facade.findClass(fqn, resolveScope) ?: return@forEach
            AnnotatedElementsSearch.searchPsiFields(annotation, searchScope).findAll().forEach { field ->
                dubboReferenceEndpoint(field)?.let { endpoint ->
                    out += Record(endpoint, pointers.createSmartPsiElementPointer(field))
                }
            }
        }

        return out.sortedWith(compareBy({ it.endpoint.httpMethod }, { it.endpoint.path }, { it.endpoint.methodName }))
    }

    /** 类上的 `@RequestMapping` 前缀；没写时用空串占位 */
    private fun classPathPrefixes(psiClass: PsiClass): List<String> {
        val annotation = psiClass.getAnnotation(SpringMappings.REQUEST_MAPPING) ?: return listOf("")
        val paths = annotationStrings(annotation, SpringMappings.PATH_ATTRIBUTES)
        return paths.ifEmpty { listOf("") }
    }

    /**
     * 带映射注解的方法：类自身 + 父类 + 接口（Spring 支持注解写在接口方法上）。
     */
    private fun annotatedMethods(psiClass: PsiClass): List<PsiMethod> {
        val out = mutableListOf<PsiMethod>()
        val visited = HashSet<String>()

        fun visit(current: PsiClass?) {
            if (current == null) {
                return
            }
            val key = current.qualifiedName ?: current.name
            if (key == null || key == "java.lang.Object" || !visited.add(key)) {
                return
            }
            current.methods
                .filter { method -> SpringMappings.METHOD_ANNOTATIONS.keys.any { method.hasAnnotation(it) } }
                .forEach { out += it }
            current.supers.forEach { visit(it) }
        }

        visit(psiClass)
        return out
    }

    /** 一个方法可能产生多条记录：多个路径 × 多个 HTTP 方法 */
    private fun endpointsOf(controller: PsiClass, method: PsiMethod, basePaths: List<String>): List<ApiEndpoint> {
        val specific = SpringMappings.METHOD_ANNOTATIONS.entries
            .filter { it.value != SpringMappings.REQUEST }
            .firstOrNull { method.hasAnnotation(it.key) }
        val entry = specific
            ?: SpringMappings.METHOD_ANNOTATIONS.entries
                .firstOrNull { it.value == SpringMappings.REQUEST && method.hasAnnotation(it.key) }
            ?: return emptyList()
        val annotation = method.getAnnotation(entry.key) ?: return emptyList()

        val subPaths = annotationStrings(annotation, SpringMappings.PATH_ATTRIBUTES).ifEmpty { listOf("") }
        val httpMethods = if (entry.value == SpringMappings.REQUEST) {
            SpringMappings.parseRequestMethods(annotation.findDeclaredAttributeValue("method")?.text.orEmpty())
        } else {
            listOf(entry.value)
        }

        val className = controller.name.orEmpty()
        val moduleName = ModuleUtilCore.findModuleForPsiElement(controller)?.name.orEmpty()
        val out = mutableListOf<ApiEndpoint>()
        basePaths.forEach { base ->
            subPaths.forEach { sub ->
                httpMethods.forEach { http ->
                    out += ApiEndpoint(
                        httpMethod = http,
                        path = SpringMappings.joinPath(base, sub),
                        className = className,
                        methodName = method.name,
                        moduleName = moduleName
                    )
                }
            }
        }
        return out
    }

    /** `@Scheduled` 方法：没有路径，把注解属性（cron/fixedRate 等）放进 detail 供搜索 */
    private fun scheduledEndpoint(method: PsiMethod): ApiEndpoint? {
        val owner = method.containingClass ?: return null
        val annotation = method.getAnnotation(SpringMappings.SCHEDULED) ?: return null
        return ApiEndpoint(
            httpMethod = SpringMappings.SCHEDULE,
            path = "",
            className = owner.name.orEmpty(),
            methodName = method.name,
            moduleName = ModuleUtilCore.findModuleForPsiElement(owner)?.name.orEmpty(),
            detail = annotationDetail(annotation)
        )
    }

    /**
     * Dubbo 提供者：`@DubboService` 实现类里的每个公开方法一条记录。
     * Dubbo 没有路径概念，`path` 为空、列表显示 `类名#方法名`，version / group 等属性进 detail。
     */
    private fun dubboServiceEndpoints(psiClass: PsiClass): List<Pair<ApiEndpoint, PsiMethod>> {
        val annotation = DubboMappings.SERVICE_ANNOTATIONS.firstNotNullOfOrNull { psiClass.getAnnotation(it) }
            ?: return emptyList()
        val className = psiClass.name.orEmpty()
        val moduleName = ModuleUtilCore.findModuleForPsiElement(psiClass)?.name.orEmpty()
        val detail = annotationDetail(annotation)
        return psiClass.methods
            .filter { method ->
                !method.isConstructor && !method.hasModifierProperty("static") &&
                    !DubboMappings.isObjectMethod(method.name)
            }
            .map { method ->
                ApiEndpoint(
                    httpMethod = DubboMappings.DUBBO,
                    path = "",
                    className = className,
                    methodName = method.name,
                    moduleName = moduleName,
                    detail = detail
                ) to method
            }
    }

    /** Dubbo 消费者：`@DubboReference` 字段，detail 记录引用的接口类型与 version / group */
    private fun dubboReferenceEndpoint(field: PsiField): ApiEndpoint? {
        val owner = field.containingClass ?: return null
        val annotation = DubboMappings.REFERENCE_ANNOTATIONS.firstNotNullOfOrNull { field.getAnnotation(it) }
            ?: return null
        val detail = listOf(field.type.canonicalText, annotationDetail(annotation))
            .filter { it.isNotEmpty() }
            .joinToString("  ")
        return ApiEndpoint(
            httpMethod = DubboMappings.DUBBO_REFERENCE,
            path = "",
            className = owner.name.orEmpty(),
            methodName = field.name,
            moduleName = ModuleUtilCore.findModuleForPsiElement(owner)?.name.orEmpty(),
            detail = detail
        )
    }

    /** 注解属性拼成 `name=value` 串（@Scheduled 的 cron、@DubboService 的 version / group 等） */
    private fun annotationDetail(annotation: PsiAnnotation): String =
        annotation.parameterList.attributes
            .filter { attr -> !attr.value?.text.isNullOrBlank() }
            .joinToString(", ") { attr ->
                "${attr.name}=${attr.value?.text?.trim()?.trim('"').orEmpty()}"
            }

    // ------------------------------------------------------------------ 注解取值

    /** 读取注解上的路径属性（值可能是字面量、常量或数组） */
    private fun annotationStrings(annotation: PsiAnnotation, attributes: List<String>): List<String> {
        val out = mutableListOf<String>()
        attributes.forEach { name ->
            val value = annotation.findDeclaredAttributeValue(name) ?: return@forEach
            collectStrings(value, out)
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun collectStrings(value: PsiAnnotationMemberValue, out: MutableList<String>) {
        when (value) {
            is PsiArrayInitializerMemberValue -> value.initializers.forEach { collectStrings(it, out) }
            is PsiExpression -> {
                val constant = runCatching {
                    JavaPsiFacade.getInstance(project).constantEvaluationHelper.computeConstantExpression(value)
                }.getOrNull()
                if (constant is String) {
                    out += constant
                    return
                }
                // 常量跨模块解析不到时退化为源码文本（去掉引号与拼接表达式）
                val text = value.text.trim().trim('"')
                if (text.isNotEmpty() && !text.contains("+")) {
                    out += text
                }
            }

            else -> Unit
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SpringApiService::class.java)

        private const val REFRESH_DELAY_MS = 800L

        /** 源文件改动前后的轻量相关性判断：只看文本里有没有这些注解名，不触碰索引 */
        private val ANNOTATION_TOKENS: List<String> = buildList {
            addAll(SpringMappings.CLASS_ANNOTATIONS)
            addAll(SpringMappings.METHOD_ANNOTATIONS.keys)
            add(SpringMappings.SCHEDULED)
            addAll(DubboMappings.SERVICE_ANNOTATIONS)
            addAll(DubboMappings.REFERENCE_ANNOTATIONS)
            addAll(
                listOf(
                    "@RestController", "@Controller", "@RequestMapping", "@GetMapping",
                    "@PostMapping", "@PutMapping", "@DeleteMapping", "@PatchMapping",
                    "@Scheduled", "@DubboService", "@DubboReference", "@Reference", "@Service",
                )
            )
        }

        fun getInstance(project: Project): SpringApiService = project.service<SpringApiService>()
    }
}
