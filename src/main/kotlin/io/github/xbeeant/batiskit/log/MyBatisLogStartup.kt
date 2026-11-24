package io.github.xbeeant.batiskit.log

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.xbeeant.batiskit.settings.ModuleToggles

/**
 * 启动入口：提前把 [MyBatisLogService] 建起来。
 *
 * 服务构造时订阅 ExecutionManager，所以第一次点「运行」之前监听就已经挂好了——
 * 不必等用户先打开工具窗口，否则会漏掉程序启动阶段打印的 SQL。
 */
class MyBatisLogStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!ModuleToggles.isEnabled(ModuleToggles.LOG)) return
        MyBatisLogService.getInstance(project)
    }
}
