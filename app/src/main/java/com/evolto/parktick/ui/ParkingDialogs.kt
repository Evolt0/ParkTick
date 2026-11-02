package com.evolto.parktick.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.evolto.parktick.AppGraph
import com.evolto.parktick.data.PlateText
import com.evolto.parktick.domain.ParkingDuration
import com.evolto.parktick.domain.ParkingRecord
import com.evolto.parktick.media.PhotoDecoder
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PhotoLoad(val bitmap: Bitmap? = null, val error: String? = null)

@Composable
internal fun PhotoPreview(file: File, maxEdge: Int) {
    val photo by produceState(PhotoLoad(), file.absolutePath, maxEdge) {
        value = try {
            PhotoLoad(bitmap = withContext(Dispatchers.IO) { PhotoDecoder.decode(file, maxEdge) })
        } catch (_: IOException) {
            PhotoLoad(error = "照片暂时无法读取")
        }
    }
    val image = photo.bitmap
    when {
        image != null -> Image(image.asImageBitmap(), "车辆照片", Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
        photo.error != null -> Text(photo.error!!)
        else -> LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
internal fun PlateConfirmationDialog(
    graph: AppGraph,
    state: CaptureState,
    onPlate: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val photo = state.photo ?: return
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val cancel = {
        if (!state.saving) {
            focus.clearFocus()
            keyboard?.hide()
            onCancel()
        }
    }
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.safeDrawingPadding().imePadding().padding(12.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TextButton(onClick = cancel, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
                Text("确认车牌", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.heightIn(max = 120.dp)) { PhotoPreview(graph.photos.file(photo.photoName), 480) }
                OutlinedTextField(
                    value = state.plate,
                    onValueChange = onPlate,
                    enabled = !state.saving,
                    label = { Text("车牌号码") },
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("识别错误可直接修改，无需重新拍照。")
                Text("开始时间：${timestamp(photo.capturedAt.wallTimeMs)}")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = { focus.clearFocus(); keyboard?.hide(); onConfirm() }, enabled = !state.saving && PlateText.normalize(state.plate).isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (state.saving) "正在保存…" else "确认登记")
                }
                OutlinedButton(onClick = cancel, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新拍照") }
            }
        }
    }
}

@Composable
internal fun EndParkingDialog(record: ParkingRecord, duration: ParkingDuration, busy: Boolean, error: String?, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.safeDrawingPadding().imePadding().padding(12.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("确认结束停车", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(record.plate, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("已停 ${durationLabel(duration)}", fontSize = 22.sp)
                Text("确认结束本次停车？结束后将删除车辆照片，保留车牌和停车时间记录。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (busy) "正在保存并清理…" else "确认结束") }
                OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消，继续计时") }
            }
        }
    }
}
