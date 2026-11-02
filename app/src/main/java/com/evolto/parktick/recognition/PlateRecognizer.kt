package com.evolto.parktick.recognition

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import com.evolto.parktick.media.PhotoDecoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PlateCandidate(
    val code: String,
    val confidence: Float,
    val type: Int,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)

internal object NativeBridge {
    init { System.loadLibrary("parktick_lpr") }
    external fun create(directory: String): Long
    external fun recognize(handle: Long, image: Bitmap): Array<PlateCandidate>
    external fun destroy(handle: Long)
}

class PlateRecognizer(context: Context) {
    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "parking-recognition") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var handle = 0L
    private val closed = AtomicBoolean(false)
    private val modelNames = listOf(
        "b320_backbone_h.mnn", "b320_header_h.mnn", "b640x_backbone_h.mnn",
        "b640x_head_h.mnn", "litemodel_cls_96xh.mnn", "rpv3_mdict_160h.mnn",
    )

    suspend fun recognize(photo: File): List<PlateCandidate> = withContext(dispatcher) {
        check(!closed.get()) { "离线识别器已关闭" }
        currentCoroutineContext().ensureActive()
        if (handle == 0L) initialize()
        val image = PhotoDecoder.decode(photo, 1920)
        try {
            currentCoroutineContext().ensureActive()
            NativeBridge.recognize(handle, image).asList()
        } finally {
            image.recycle()
        }
    }

    private fun initialize() {
        val directory = File(context.noBackupFilesDir, "lpr-models-v1")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建离线模型目录")
        for (name in modelNames) {
            val target = File(directory, name)
            if (!target.isFile || target.length() == 0L) {
                context.assets.open("lpr/$name").use { input ->
                    val temporary = File(directory, "$name.pending")
                    temporary.outputStream().use { input.copyTo(it) }
                    if (!temporary.renameTo(target)) throw IOException("无法安装离线模型")
                }
            }
        }
        val created = NativeBridge.create(directory.absolutePath)
        check(created != 0L) { "离线识别模型初始化失败" }
        handle = created
    }


    fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute {
            if (handle != 0L) {
                NativeBridge.destroy(handle)
                handle = 0L
            }
        }
        executor.shutdown()
    }
}
