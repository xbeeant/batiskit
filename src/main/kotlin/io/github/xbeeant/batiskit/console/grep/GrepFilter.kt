package io.github.xbeeant.batiskit.console.grep

import com.intellij.execution.filters.InputFilter
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Grep 子控制台的转发监听器（对标原版 `GrepFilterListener`）：
 * 父控制台的每一段输出都会先经 [MyGrepFilter] 流到这里。
 */
interface GrepFilterListener {
    /** 一段输出（可能是半行，也可能带多行） */
    fun process(text: String, contentType: ConsoleViewContentType)

    /** 父控制台 / 子控制台被清空时丢弃半行缓冲 */
    fun clear()

    fun dispose()
}

/**
 * 对标原版 `GrepFilter`：挂在每个控制台输入过滤管道上的 `InputFilter`，
 * 把流经的文本**原样**分发给所有 Grep 子控制台的监听器；自己不改写、不丢弃任何文本。
 *
 * 子控制台因此「寄生」在父控制台的输出管道上：父打印 → 过滤器分发 → 命中的行打印进子控制台。
 */
class MyGrepFilter : InputFilter {

    private val listeners = CopyOnWriteArrayList<GrepFilterListener>()

    override fun applyFilter(
        text: String,
        contentType: ConsoleViewContentType,
    ): List<Pair<String, ConsoleViewContentType>>? {
        for (listener in listeners) {
            listener.process(text, contentType)
        }
        return null
    }

    fun addListener(listener: GrepFilterListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: GrepFilterListener) {
        listeners.remove(listener)
    }
}

/**
 * 每个控制台一个 [MyGrepFilter]（控制台创建时由输入过滤 provider 注册进来），
 * 另外记录每个控制台下挂着的 Grep 子控制台（链式 grep、Inactive 标记、tab 描述都要用）。
 */
@Service(Service.Level.PROJECT)
class GrepFilterRegistry {

    private val filters = WeakHashMap<ConsoleView, MyGrepFilter>()
    private val childs = WeakHashMap<ConsoleView, MutableList<MyGrepConsoleView>>()

    /** provider 在控制台初始化时调用；同一控制台只建一份 */
    fun register(console: ConsoleView): MyGrepFilter = synchronized(this) {
        val filter = filters.getOrPut(console) { MyGrepFilter() }
        Disposer.register(console) {
            synchronized(this) {
                filters.remove(console)
                childs.remove(console)
            }
        }
        filter
    }

    fun filterOf(console: ConsoleView): MyGrepFilter? = synchronized(this) { filters[console] }

    fun registerChild(parent: ConsoleView, child: MyGrepConsoleView) = synchronized(this) {
        childs.getOrPut(parent) { mutableListOf() }.add(child)
        Disposer.register(child) {
            synchronized(this) { childs[parent]?.remove(child) }
        }
    }

    fun findChildGreps(parent: ConsoleView): List<MyGrepConsoleView> = synchronized(this) {
        childs[parent]?.toList() ?: emptyList()
    }

    companion object {
        fun getInstance(project: Project): GrepFilterRegistry = project.service()
    }
}
