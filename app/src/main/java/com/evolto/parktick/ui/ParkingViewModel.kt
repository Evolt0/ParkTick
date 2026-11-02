package com.evolto.parktick.ui

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.evolto.parktick.AppGraph
import com.evolto.parktick.domain.CapturedPhoto
import com.evolto.parktick.domain.ClockSnapshot
import com.evolto.parktick.domain.FinishResult
import com.evolto.parktick.domain.ParkingRecord
import com.evolto.parktick.domain.PhotoFile
import com.evolto.parktick.domain.RegisterResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ParkingListState(
    val records: List<ParkingRecord> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

// Identity lets recovery dismiss its observed cleanup warning without clearing a newer notice.
internal class ParkingNotice(val message: String, val cleanupPending: Boolean = false)

internal data class CaptureState(
    val busy: Boolean = false,
    val recognizing: Boolean = false,
    val saving: Boolean = false,
    val photo: CapturedPhoto? = null,
    val plate: String = "",
    val error: String? = null,
)

internal sealed interface NavigationEvent {
    data object Registered : NavigationEvent
    data class OpenRecord(val id: String) : NavigationEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class ParkingViewModel(private val graph: AppGraph, context: Context) : ViewModel() {
    private val applicationContext = context.applicationContext
    val activeQuery = MutableStateFlow("")
    val historyQuery = MutableStateFlow("")
    val active = activeQuery.flatMapLatest(graph.repository::observeActive)
        .map { ParkingListState(it, loading = false) }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(ParkingListState(loading = false, error = "无法读取本机在停记录，请返回后重试"))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ParkingListState())
    val history = historyQuery.flatMapLatest(graph.repository::observeHistory)
        .map { ParkingListState(it, loading = false) }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(ParkingListState(loading = false, error = "无法读取本机历史记录，请返回后重试"))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ParkingListState())
    private val captureState = MutableStateFlow(CaptureState())
    val capture = captureState.asStateFlow()
    val problem = MutableStateFlow<ParkingNotice?>(null)
    val endTarget = MutableStateFlow<ParkingRecord?>(null)
    val ending = MutableStateFlow(false)
    val endError = MutableStateFlow<String?>(null)
    private val navigationChannel = Channel<NavigationEvent>(Channel.BUFFERED)
    val navigation = navigationChannel.receiveAsFlow()
    private var captureToken = 0L
    private var pendingFile: PhotoFile? = null

    fun setPlate(value: String) { captureState.value = captureState.value.copy(plate = value, error = null) }

    fun recover() {
        val notice = problem.value
        viewModelScope.launch {
            try {
                graph.repository.recover()
                if (notice?.cleanupPending == true) problem.compareAndSet(notice, null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                problem.value = ParkingNotice("照片清理未完成，重新打开应用时继续处理", cleanupPending = true)
            }
        }
    }

    fun cameraFailure(message: String) { captureState.value = captureState.value.copy(busy = false, error = message) }

    fun takePhoto(imageCapture: ImageCapture) {
        if (captureState.value.busy || captureState.value.saving || captureState.value.photo != null) return
        val token = ++captureToken
        captureState.value = CaptureState(busy = true)
        // Complete file creation even if this ViewModel is cleared, then reject a stale token.
        graph.applicationScope.launch(Dispatchers.Main.immediate) {
            val output = try {
                withContext(Dispatchers.IO) { graph.photos.newFile() }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (token == captureToken) cameraFailure("无法保存照片，请检查剩余空间后重试")
                return@launch
            }
            if (token != captureToken) { discard(output.photoName); return@launch }
            pendingFile = output
            var captureTime: ClockSnapshot? = null
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(output.file).build(),
                ContextCompat.getMainExecutor(applicationContext),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onCaptureStarted() { captureTime = graph.clock.snapshot() }
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        if (pendingFile?.id == output.id) pendingFile = null
                        val anchor = captureTime
                        if (token != captureToken || anchor == null) {
                            discard(output.photoName)
                            if (token == captureToken) cameraFailure("无法取得拍照时间，请重新拍照")
                            return
                        }
                        val photo = CapturedPhoto(output.id, output.photoName, anchor)
                        captureState.value = CaptureState(busy = true, recognizing = true, photo = photo)
                        recognize(token, photo)
                    }
                    override fun onError(error: ImageCaptureException) {
                        if (pendingFile?.id == output.id) pendingFile = null
                        discard(output.photoName)
                        if (token == captureToken) cameraFailure("拍照失败，请重新拍摄")
                    }
                },
            )
        }
    }

    private fun recognize(token: Long, photo: CapturedPhoto) {
        viewModelScope.launch {
            try {
                val results = graph.recognizer.recognize(graph.photos.file(photo.photoName))
                if (token == captureToken) {
                    captureState.value = CaptureState(
                        photo = photo,
                        plate = results.maxByOrNull { it.confidence }?.code.orEmpty(),
                        error = if (results.isEmpty()) "未识别到车牌，请手动填写或重新拍照" else null,
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                recognitionFailure(token, photo, "离线识别失败，请重新拍照或手动填写车牌")
            } catch (_: LinkageError) {
                recognitionFailure(token, photo, "本机离线识别库加载失败，请检查安装包与设备兼容性")
            }
        }
    }

    private fun recognitionFailure(token: Long, photo: CapturedPhoto, message: String) {
        if (token == captureToken) captureState.value = CaptureState(photo = photo, error = message)
    }

    fun confirmPlate() {
        val current = captureState.value
        val photo = current.photo ?: return
        if (current.busy || current.saving) return
        val token = captureToken
        captureState.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            val result = graph.repository.register(photo, current.plate)
            if (token != captureToken) return@launch
            when (result) {
                is RegisterResult.Created -> {
                    captureState.value = CaptureState()
                    activeQuery.value = ""
                    navigationChannel.trySend(NavigationEvent.Registered)
                }
                is RegisterResult.AlreadyActive -> {
                    captureState.value = CaptureState()
                    problem.value = result.cleanupWarning?.let { ParkingNotice(it, cleanupPending = true) }
                    navigationChannel.trySend(NavigationEvent.OpenRecord(result.record.id))
                }
                is RegisterResult.Failure -> captureState.value = current.copy(error = result.message)
            }
        }
    }

    fun cancelCapture() {
        captureToken++
        captureState.value.photo?.let { discard(it.photoName) }
        captureState.value = CaptureState()
        // An in-flight CameraX callback still owns pendingFile; it discards the result on arrival.
    }

    private fun discard(name: String) {
        graph.applicationScope.launch {
            try {
                graph.photos.discard(name)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                problem.value = ParkingNotice("临时照片清理未完成，重新打开应用时继续处理", cleanupPending = true)
            }
        }
    }

    fun requestEnd(record: ParkingRecord) {
        endError.value = null
        endTarget.value = record
    }

    fun cancelEnd() {
        if (!ending.value) { endTarget.value = null; endError.value = null }
    }

    fun finishParking() {
        val record = endTarget.value ?: return
        if (ending.value) return
        val confirmationTime = graph.clock.snapshot()
        ending.value = true
        viewModelScope.launch {
            try {
                when (val result = graph.repository.finish(record.id, confirmationTime)) {
                    is FinishResult.Finished -> {
                        endTarget.value = null
                        problem.value = ParkingNotice("停车已结束，车辆照片已删除")
                    }
                    is FinishResult.CleanupPending -> {
                        endTarget.value = null
                        problem.value = ParkingNotice(result.message, cleanupPending = true)
                    }
                    is FinishResult.Failure -> endError.value = result.message
                }
            } finally {
                ending.value = false
            }
        }
    }

    override fun onCleared() {
        cancelCapture()
        navigationChannel.close()
        super.onCleared()
    }
}
