package com.evolto.parktick.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.evolto.parktick.AppGraph
import com.evolto.parktick.camera.CameraScreen
import com.evolto.parktick.domain.ClockSnapshot
import com.evolto.parktick.domain.DurationBasis
import com.evolto.parktick.domain.ParkingDuration
import com.evolto.parktick.domain.ParkingRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive

private val LocalZone = staticCompositionLocalOf { TimeZone.getDefault().id }
private data class RecordLoad(val record: ParkingRecord? = null, val loading: Boolean = true, val error: String? = null)

@Composable
fun ParkTickApp(graph: AppGraph, onLeaveApp: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val vm: ParkingViewModel = viewModel(factory = remember(graph) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ParkingViewModel(graph, context) as T
        }
    })
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "active"
    val capture by vm.capture.collectAsStateWithLifecycle()
    val endTarget by vm.endTarget.collectAsStateWithLifecycle()
    val ending by vm.ending.collectAsStateWithLifecycle()
    val endError by vm.endError.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.recover()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val now by produceState(graph.clock.snapshot(), graph, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                value = graph.clock.snapshot()
                delay(1_000)
            }
        }
    }
    LaunchedEffect(vm, nav, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.navigation.collect { event ->
                when (event) {
                    NavigationEvent.Registered -> nav.navigate("active") { popUpTo("active"); launchSingleTop = true }
                    is NavigationEvent.OpenRecord -> nav.navigate("record/${event.id}") { popUpTo("active") }
                }
            }
        }
    }
    val back: () -> Unit = {
        if (route == "camera") vm.cancelCapture()
        if (!nav.popBackStack()) onLeaveApp()
    }
    BackHandler(onBack = back)
    val typography = Typography(
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
        bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
        bodySmall = MaterialTheme.typography.bodySmall.copy(fontSize = 16.sp, lineHeight = 23.sp),
        labelLarge = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp),
        titleLarge = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
        titleMedium = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
    )
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1565C0)), typography = typography) {
        CompositionLocalProvider(LocalZone provides TimeZone.getDefault().id) {
            Scaffold(
                modifier = Modifier.imePadding(),
                topBar = {
                    Surface {
                        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                    Text("返回")
                                }
                                Spacer(Modifier.weight(1f))
                                if (route == "active") {
                                    TextButton(onClick = { nav.navigate("history") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("历史") }
                                }
                            }
                            Text(
                                when (route) { "active" -> "在停车辆"; "camera" -> "拍照登记"; "history" -> "历史记录"; else -> "停车详情" },
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                            )
                        }
                    }
                },
                bottomBar = {
                    if (route == "active") {
                        Surface(tonalElevation = 3.dp) {
                            Button(
                                onClick = { nav.navigate("camera") },
                                modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 56.dp),
                            ) { Text("拍照登记") }
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    problem?.let { message ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(message.message)
                                TextButton(onClick = { vm.problem.value = null }, modifier = Modifier.align(Alignment.End)) { Text("知道了") }
                            }
                        }
                    }
                    NavHost(nav, startDestination = "active", modifier = Modifier.weight(1f)) {
                        composable("active") {
                            val state by vm.active.collectAsStateWithLifecycle()
                            val query by vm.activeQuery.collectAsStateWithLifecycle()
                            ParkingList(state, query, { vm.activeQuery.value = it }, now, graph, historical = false,
                                onOpen = { nav.navigate("record/${it.id}") }, onEnd = vm::requestEnd)
                        }
                        composable("history") {
                            val state by vm.history.collectAsStateWithLifecycle()
                            val query by vm.historyQuery.collectAsStateWithLifecycle()
                            ParkingList(state, query, { vm.historyQuery.value = it }, now, graph, historical = true,
                                onOpen = { nav.navigate("record/${it.id}") }, onEnd = vm::requestEnd)
                        }
                        composable("camera") { CameraScreen(vm, capture) }
                        composable("record/{id}") { backStack ->
                            val id = backStack.arguments?.getString("id").orEmpty()
                            val recordState by remember(id) {
                                graph.repository.observeRecord(id).map { RecordLoad(it, loading = false) }
                                    .catch { error ->
                                        if (error is CancellationException) throw error
                                        emit(RecordLoad(loading = false, error = "无法读取本机停车记录，请返回后重试"))
                                    }
                            }.collectAsStateWithLifecycle(initialValue = RecordLoad())
                            val record = recordState.record
                            when {
                                recordState.loading -> LoadingPanel("正在读取记录…")
                                recordState.error != null -> NoticePanel(recordState.error!!)
                                record == null -> NoticePanel("停车记录不存在")
                                else -> ParkingDetail(record, now, graph, vm::requestEnd)
                            }
                        }
                    }
                }
            }
            if (capture.photo != null && !capture.recognizing) {
                PlateConfirmationDialog(graph, capture, vm::setPlate, vm::confirmPlate, vm::cancelCapture)
            }
            endTarget?.let { target ->
                EndParkingDialog(target, graph.clock.duration(target.startedAt, now), ending, endError, vm::cancelEnd, vm::finishParking)
            }
        }
    }
}

@Composable
private fun ParkingList(
    state: ParkingListState,
    query: String,
    onQuery: (String) -> Unit,
    now: ClockSnapshot,
    graph: AppGraph,
    historical: Boolean,
    onOpen: (ParkingRecord) -> Unit,
    onEnd: (ParkingRecord) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "search") {
            OutlinedTextField(
                value = query, onValueChange = onQuery, label = { Text("搜索车牌") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            state.loading -> item { LoadingPanel("正在读取车辆…") }
            state.error != null -> item { NoticePanel(state.error) }
            state.records.isEmpty() -> item { NoticePanel(if (query.isNotBlank()) "未找到匹配的车牌" else if (historical) "暂无历史记录" else "暂无在停车辆，点击“拍照登记”添加") }
            else -> items(state.records, key = { it.id }) { record ->
                    ElevatedCard(onClick = { onOpen(record) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(record.plate, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            val duration = record.duration ?: graph.clock.duration(record.startedAt, now)
                            Text(durationLabel(duration), fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                            Text("开始：${timestamp(record.startedAt.wallTimeMs)}")
                            record.endedAtWallMs?.let { Text("结束：${timestamp(it)}") }
                            if (!historical) {
                                OutlinedButton(onClick = { onEnd(record) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("结束停车") }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun ParkingDetail(record: ParkingRecord, now: ClockSnapshot, graph: AppGraph, onEnd: (ParkingRecord) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(record.plate, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(if (record.isActive) "正在停车" else "已结束", color = MaterialTheme.colorScheme.primary)
        val duration = record.duration ?: graph.clock.duration(record.startedAt, now)
        Text(durationLabel(duration), fontSize = 24.sp, fontWeight = FontWeight.Medium)
        if (duration.basis == DurationBasis.WALL_CLOCK_ESTIMATE) Text("重启后按系统时间估算")
        if (duration.clockChanged) Text("检测到系统时间变化，时长依据已单独记录")
        Text("开始时间\n${timestamp(record.startedAt.wallTimeMs)}")
        record.endedAtWallMs?.let { Text("结束时间\n${timestamp(it)}") }
        if (record.isActive) {
            record.photoName?.let { PhotoPreview(graph.photos.file(it), 1200) }
            Button(onClick = { onEnd(record) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("结束停车") }
        }
    }
}

@Composable
internal fun LoadingPanel(message: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(message)
    }
}

@Composable
internal fun NoticePanel(message: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Text(message) }
}

@Composable
internal fun timestamp(milliseconds: Long): String {
    val zone = LocalZone.current
    return remember(milliseconds, zone) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(milliseconds))
    }
}

internal fun durationLabel(duration: ParkingDuration): String {
    val millis = duration.milliseconds ?: return "时长无法准确恢复"
    val seconds = millis / 1_000
    val days = seconds / 86_400
    val hours = seconds / 3_600 % 24
    val minutes = seconds / 60 % 60
    val remaining = seconds % 60
    return buildString {
        if (duration.basis == DurationBasis.WALL_CLOCK_ESTIMATE) append("约 ")
        if (days > 0) append(days).append("天 ")
        fun two(value: Long) { append(('0'.code + (value / 10).toInt()).toChar()); append(('0'.code + (value % 10).toInt()).toChar()) }
        two(hours); append(':'); two(minutes); append(':'); two(remaining)
    }
}

