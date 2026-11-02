package com.evolto.parktick.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.evolto.parktick.ui.CaptureState
import com.evolto.parktick.ui.ParkingViewModel

@Composable
internal fun CameraScreen(vm: ParkingViewModel, state: CaptureState) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var requestedPermission by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Rotation disposes this composition; draft cancellation belongs to explicit actions or ViewModel clearing.
    LaunchedEffect(Unit) {
        if (!permission && !requestedPermission) {
            requestedPermission = true
            request.launch(Manifest.permission.CAMERA)
        }
    }
    if (!permission) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("拍照登记需要相机权限。照片仅保存在本机，不上传、不写入相册。")
            Button(onClick = { requestedPermission = true; request.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("允许使用相机") }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("打开应用权限设置") }
        }
        return
    }
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var ready by remember { mutableStateOf(false) }
    DisposableEffect(owner, view, capture) {
        ready = false
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed) {
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    ready = true
                } catch (_: Exception) {
                    vm.cameraFailure("相机暂时无法打开，请返回后重新进入")
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            ready = false
            disposed = true
            provider?.unbind(preview, capture)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val previewHeight = (maxHeight * 0.6f).coerceAtLeast(160.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("将车牌放在画面中央，保持清晰后拍照", modifier = Modifier.padding(horizontal = 16.dp))
            AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(previewHeight))
            state.error?.takeIf { state.photo == null }?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (state.busy) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(if (state.recognizing) "正在本机识别车牌…" else "正在保存照片…")
                }
            }
            Button(
                onClick = {
                    capture.targetRotation = view.display?.rotation ?: Surface.ROTATION_0
                    vm.takePhoto(capture)
                },
                enabled = ready && !state.busy && !state.saving && state.photo == null,
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp),
            ) { Text("拍照") }
        }
    }
}
