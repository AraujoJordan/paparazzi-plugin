package com.getyourguide.paparazzi

import com.getyourguide.paparazzi.service.Snapshot
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.CaretModel
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.modules
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.idea.base.facet.isTestModule
import org.jetbrains.kotlin.idea.util.projectStructure.getModule
import org.jetbrains.kotlin.idea.util.projectStructure.getModuleDir
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElement
import java.io.File
import java.util.concurrent.Callable

internal fun VirtualFile.toSnapshots(project: Project, isFailure: Boolean): List<Snapshot> {
    val psiFile = PsiManager.getInstance(project).findFile(this) as? PsiClassOwner
    return if (psiFile != null) {
        val snapshots = if (isFailure) project.failureDiffSnapshots(this) else project.recordedSnapshots(this)
        psiFile.toSnapshots(snapshots, isFailure)
    } else emptyList()
}

internal fun Project.isToolWindowOpened(): Boolean {
    return ToolWindowManager.getInstance(this).getToolWindow(TOOL_WINDOW_ID)?.isVisible == true
}

internal fun PsiElement.containingMethod(): UMethod? {
    return this.parents.toList().map { it.toUElement() }.find { it is UMethod } as? UMethod
}

internal val FileEditor.caretModel: CaretModel?
    get() = (this as? TextEditor)?.editor?.caretModel

internal fun VirtualFile.psiElement(project: Project, offset: Int): PsiElement? {
    val psiFile = PsiManager.getInstance(project).findFile(this)
    return psiFile?.findElementAt(offset)
}

internal fun PsiElement.file(): VirtualFile? = containingFile.virtualFile

internal fun VirtualFile.methods(project: Project): List<String> {
    val psiClassOwner = PsiManager.getInstance(project).findFile(this) as? PsiClassOwner
    return psiClassOwner?.methods() ?: emptyList()
}

/**
 * Get the test module path that contains the file, searching in all project test modules.
 */
internal fun Project.testModulePath(file: VirtualFile): String? {
    return modules
        .asSequence()
        .filter { it.isTestModule }
        .firstOrNull { File(it.getModuleDir()).walk().find { it.path == file.path } != null }
        ?.getModuleDir()
}

internal inline fun <reified T> nonBlocking(crossinline asyncAction: () -> T, crossinline uiThreadAction: (T) -> Unit) {
    ReadAction.nonBlocking(Callable {
        asyncAction()
    }).finishOnUiThread(ModalityState.defaultModalityState()) { T ->
        uiThreadAction(T)
    }.submit(AppExecutorUtil.getAppExecutorService())
}

private fun PsiClassOwner.toSnapshots(snapshots: List<VirtualFile>, isFailure: Boolean): List<Snapshot> {
    val prefix = if (isFailure) "delta-" else ""
    return classes.flatMap { psiClass ->
        val name = "$prefix${packageName}_${psiClass.name}"
        snapshots.filter { it.name.startsWith(name) }.map {
            Snapshot(it, it.snapshotName(name))
        }
    }
}

private fun PsiClassOwner.methods(): List<String> {
    return classes.flatMap { psiClass ->
        psiClass.methods.map { it.name }
    }
}

private fun VirtualFile.snapshotName(qualifiedTestName: String): String {
    return nameWithoutExtension.substringAfter(qualifiedTestName + "_")
}

private fun Project.recordedSnapshots(file: VirtualFile): List<VirtualFile> {
    val modulePath = testModulePath(file)
    return if (modulePath != null) {
        LocalFileSystem.getInstance().findFileByPath(modulePath)?.findChild("src")?.findChild("test")
    } else {
        file.getModule(this)?.rootManager?.contentRoots?.find { it.name == "test" }
    }?.findChild("snapshots")?.findChild("images")?.children?.toList() ?: emptyList()
}

private fun Project.failureDiffSnapshots(file: VirtualFile): List<VirtualFile> {
    val modulePath = testModulePath(file)
    val projectPath = basePath
    return if (modulePath != null) {
        LocalFileSystem.getInstance().findFileByPath(modulePath)?.findChild("out")
            ?.findChild("failures")?.children?.toList() ?: emptyList()
    } else if (projectPath != null) {
        LocalFileSystem.getInstance().findFileByPath(projectPath)?.children?.flatMap {
            it.findChild("out")?.findChild("failures")?.children?.toList() ?: emptyList()
        } ?: emptyList()
    } else emptyList()
}
