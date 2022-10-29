package com.madrapps.paparazzi.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager
import com.madrapps.paparazzi.Item
import com.madrapps.paparazzi.PaparazziWindowPanel
import org.jetbrains.kotlin.idea.util.projectStructure.getModule
import java.awt.Image
import javax.imageio.ImageIO
import javax.swing.DefaultListModel

interface MainService {

    class Storage {
        // path to snapshots
        // no of screenshots to show at a time (everything at once can cause OOM)

        var isAutoChangeEnabled = true
    }

    var panel: PaparazziWindowPanel?
    val model: DefaultListModel<Item>
    val settings: Storage

    fun image(item: Item): Image

    fun zoomFitToWindow()
    fun zoomActualSize()

    fun reload()
    fun reload(file: VirtualFile)
}

@State(name = "com.madrapps.paparazzi", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class MainServiceImpl(private val project: Project) : MainService, PersistentStateComponent<MainService.Storage>,
    FileEditorManagerListener {

    private val MAX_ZOOM_WIDTH = 700
    private val MIN_ZOOM_WIDTH = 200

    private var storage = MainService.Storage()
    private val screenshotMap: MutableMap<VirtualFile, Image> = mutableMapOf()
    private var width: Int = 0

    override var panel: PaparazziWindowPanel? = null
    override val model: DefaultListModel<Item> = DefaultListModel()

    init {
        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this)
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        super.selectionChanged(event)
        if (project.service.settings.isAutoChangeEnabled) {
            event.newFile?.let { project.service.reload(it) }
        }
    }

    override fun image(item: Item): Image {
        val file = item.file
        val image = screenshotMap[file]
        return if (image == null) {
            val read = ImageIO.read(file.inputStream)
            val im = if (width == 0) {
                read
            } else {
                val width = read.width.toFloat()
                val height = read.height.toFloat()
                val newWidth = this.width
                var newHeight = (height / width * newWidth).toInt()
                if (newHeight == 0) newHeight = 20
                read.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH)
            }
            screenshotMap[file] = im
            im
        } else {
            image
        }
    }


    override fun zoomFitToWindow() {
        screenshotMap.clear()
        panel?.let {
            val tmp = it.width - 32
            width = if (tmp > MAX_ZOOM_WIDTH) {
                MAX_ZOOM_WIDTH
            } else if (tmp < MIN_ZOOM_WIDTH) {
                MIN_ZOOM_WIDTH
            } else {
                tmp
            }
            reload()
        }
    }

    override fun zoomActualSize() {
        screenshotMap.clear()
        width = 0
        reload()
    }

    override fun reload() {
        screenshotMap.clear() // FIXME enable LRU cache
        val toList = model.elements().toList()
        model.clear()
        toList.forEach { item ->
            model.addElement(item)
        }
    }

    override fun reload(file: VirtualFile) {
        screenshotMap.clear() // FIXME enable LRU cache

        val nameWithoutExtension = file.nameWithoutExtension
        println(nameWithoutExtension)

        val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiClassOwner
        if (psiFile != null) {
            model.clear()

            val images = file.getModule(project)?.rootManager?.contentRoots?.find {
                it.name == "test"
            }?.children?.find {
                it.name == "snapshots"
            }?.children?.find {
                it.name == "images"
            }?.children ?: emptyArray()

            val packageName = psiFile.packageName
            psiFile.classes.forEach { psiClass ->
                println("XXDD - packageName = $packageName")
                println("XXDD - name = ${psiClass.name}")

                val name = "${packageName}_${psiClass.name}"
                val filter = images.filter {
                    it.name.startsWith(name)
                }

                filter.forEach {
                    model.addElement(Item(it, name))
                }
            }

        }
    }

    override fun getState(): MainService.Storage {
        return storage
    }

    override fun loadState(state: MainService.Storage) {
        storage = state
    }

    override val settings: MainService.Storage
        get() = state
}

val Project.service: MainService
    get() {
        return this.getService(MainService::class.java)
    }
