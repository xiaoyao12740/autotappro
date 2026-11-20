package com.aitap.autotappro.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aitap.autotappro.model.ChannelKind
import com.aitap.autotappro.model.ChannelMode
import com.aitap.autotappro.model.ClickPoint
import com.aitap.autotappro.model.ClickProfile
import com.aitap.autotappro.model.PointActionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 点击器协调器：Service 与 Activity 共同观察的唯一状态源。
 *
 * 约定（批 1 钉死）：
 * - 不双向直接持有 Service/Activity 引用；双方只观察这里暴露的 StateFlow。
 * - 所有读写都在主线程 Handler 上串行化，避免运行中增删点位并发崩溃。
 * - 对外方法可在任意线程调用，内部投递到主线程执行；状态快照通过 StateFlow 下发。
 */
object ClickerCoordinator {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _profile = MutableStateFlow(ClickProfile())
    /** 当前配置（点位 + 全局参数）。 */
    val profile: StateFlow<ClickProfile> = _profile.asStateFlow()

    private val _runState = MutableStateFlow(RunState.Idle)
    val runState: StateFlow<RunState> = _runState.asStateFlow()

    /** 当前生效通道（无障碍 / Root / 未就绪），由 Runner 解析后回写，UI 观察。 */
    private val _activeChannel = MutableStateFlow(ChannelKind.NONE)
    val activeChannel: StateFlow<ChannelKind> = _activeChannel.asStateFlow()

    private var repository: ProfileRepository? = null

    // ---- 录制状态 ----

    /** 录制态：是否正在录、已录动作数、上一动作时间戳（用于计算动作间隔）。 */
    data class RecordingState(
        val isRecording: Boolean = false,
        val recordedCount: Int = 0,
        val lastTapAtMillis: Long = 0L
    )

    private val _recording = MutableStateFlow(RecordingState())
    val recording: StateFlow<RecordingState> = _recording.asStateFlow()

    /** 绑定 Context（Application 作用域），初始化存档与活动配置。 */
    fun initialize(context: Context) {
        val app = context.applicationContext
        repository = ProfileRepository(app)
        postToMain {
            val loaded = repository?.loadActiveProfile() ?: ClickProfile()
            _profile.update { loaded }
        }
    }

    /** 从磁盘重新加载活动配置（调试广播每次启动前刷新用；不改运行中状态）。 */
    fun refreshActiveFromDisk() {
        postToMain {
            val loaded = repository?.loadActiveProfile() ?: return@postToMain
            _profile.update { loaded }
        }
    }

    // ---- 点位变更（主线程串行）----

    fun addPoint(type: PointActionType, x1: Float, y1: Float, x2: Float = x1, y2: Float = y1) {
        postToMain {
            val current = _profile.value
            if (current.isFull) return@postToMain
            val point = ClickPoint(
                id = current.nextPointId(),
                type = type,
                x1 = x1, y1 = y1, x2 = x2, y2 = y2
            )
            val next = current.copy(points = current.points.toMutableList().apply { add(point) })
            _profile.update { next }
        }
    }

    fun updatePoint(pointId: Int, mutate: (ClickPoint) -> Unit) {
        postToMain {
            val current = _profile.value
            val point = current.points.firstOrNull { it.id == pointId } ?: return@postToMain
            mutate(point)
            _profile.update { current.copy() }
        }
    }

    fun removePoint(pointId: Int) {
        postToMain {
            val current = _profile.value
            val nextList = current.points.filterNot { it.id == pointId }.toMutableList()
            _profile.update { current.copy(points = nextList) }
        }
    }

    /** 删除最后一个点位（未选中时「－」的兜底便捷操作）。 */
    fun removeLastPoint() {
        postToMain {
            val current = _profile.value
            val last = current.points.lastOrNull() ?: return@postToMain
            val nextList = current.points.filterNot { it.id == last.id }.toMutableList()
            _profile.update { current.copy(points = nextList) }
        }
    }

    /** 清空当前全部点位（录制开始前重置为干净序列）。 */
    fun clearPoints() {
        postToMain { _profile.update { it.copy(points = mutableListOf()) } }
    }

    /**
     * 开始录制：运行/暂停态禁止；清空旧点位后进入录制态。
     * @return true 已进入录制；false 当前状态不允许。
     */
    fun startRecording(): Boolean {
        if (_recording.value.isRecording) return false
        if (_runState.value == RunState.Running || _runState.value == RunState.Paused) return false
        postToMain { _profile.update { it.copy(points = mutableListOf()) } }
        _recording.value = RecordingState(isRecording = true)
        return true
    }

    /** 停止录制，保留已录点位供复现。 */
    fun stopRecording(): Int {
        if (!_recording.value.isRecording) return 0
        val count = _recording.value.recordedCount
        _recording.value = RecordingState(isRecording = false)
        return count
    }

    /**
     * 记录一个动作点：首动作间隔记 0（Runner 首轮本就不等待），其后按与上一动作的时间差记 delayMs。
     * @return 生成的点位；未在录制态返回 null。
     */
    fun addRecordedAction(
        type: PointActionType,
        x1: Float, y1: Float,
        x2: Float = x1, y2: Float = y1,
        touchDurationMs: Long = 50L,
        startedAtMillis: Long = System.currentTimeMillis()
    ): ClickPoint? {
        val state = _recording.value
        if (!state.isRecording) return null
        val delay = if (state.recordedCount == 0) 0L
        else (startedAtMillis - state.lastTapAtMillis).coerceAtLeast(0L)

        var created: ClickPoint? = null
        postToMainSync {
            val current = _profile.value
            val nextId = (current.points.maxByOrNull { it.id }?.id ?: 0) + 1
            val point = ClickPoint(
                id = nextId,
                type = type,
                x1 = x1.coerceAtLeast(0f),
                y1 = y1.coerceAtLeast(0f),
                x2 = x2.coerceAtLeast(0f),
                y2 = y2.coerceAtLeast(0f),
                delayMs = delay,
                touchDurationMs = touchDurationMs.coerceAtLeast(1L),
                repeatCount = 1
            )
            created = point
            _profile.update { current.copy(points = (current.points + point).toMutableList()) }
        }
        _recording.value = RecordingState(
            isRecording = true,
            recordedCount = state.recordedCount + 1,
            lastTapAtMillis = System.currentTimeMillis()
        )
        return created
    }

    // ---- 全局运行参数 ----

    fun updateGlobal(mutate: (ClickProfile) -> Unit) {
        postToMain {
            val current = _profile.value
            mutate(current)
            _profile.update { current.copy() }
        }
    }

    // ---- 运行状态（由 Runner/服务回写，UI 与悬浮层观察）----

    fun setRunState(state: RunState) {
        postToMain { _runState.update { state } }
    }

    // ---- 通道选择 ----

    fun channelMode(): ChannelMode = _profile.value.channelMode

    fun setChannelMode(mode: ChannelMode) {
        postToMain {
            _profile.update { it.copy(channelMode = mode) }
            // 模式切换后旧生效通道失效，先置未就绪，待下次开始运行重新解析。
            _activeChannel.update { ChannelKind.NONE }
        }
    }

    fun setActiveChannel(kind: ChannelKind) {
        postToMain { _activeChannel.update { kind } }
    }

    // ---- 存档 ----

    fun saveActiveProfile(): Result<Unit> {
        var result: Result<Unit> = Result.success(Unit)
        postToMainSync {
            val repo = repository ?: run {
                result = Result.failure(IllegalStateException("repository 未初始化"))
                return@postToMainSync
            }
            result = runCatching { repo.save(_profile.value) }
        }
        return result
    }

    /** 另存为异名副本：同名校验/副本/索引更新都在仓库层完成，UI 只调接口。 */
    fun saveActiveProfileAs(newName: String): Boolean {
        var ok = false
        postToMainSync {
            val repo = repository ?: return@postToMainSync
            runCatching {
                val saved = repo.saveAs(_profile.value, newName)
                _profile.update { saved }
                ok = true
            }.onFailure { android.util.Log.w("TapRepo", "saveAs 失败: ${it.message}") }
        }
        return ok
    }

    fun loadProfile(name: String): Boolean {
        var ok = false
        postToMainSync {
            val repo = repository ?: return@postToMainSync
            val loaded = repo.load(name) ?: return@postToMainSync
            _profile.update { loaded }
            ok = true
        }
        return ok
    }

    fun listProfiles(): List<ClickProfile> =
        repository?.loadAll() ?: emptyList()

    fun deleteProfile(name: String) {
        postToMain { repository?.delete(name) }
    }

    fun setPanelCollapsed(collapsed: Boolean) {
        postToMain { _profile.update { it.copy(panelCollapsed = collapsed) } }
    }

    /** 悬浮窗拖拽后是否自动吸附到屏幕侧边；切换后即时落盘。 */
    fun setSnapToEdge(enabled: Boolean) {
        postToMain {
            _profile.update { it.copy(snapToEdge = enabled) }
            persistActiveAsync()
        }
    }

    /** 是否在悬浮层显示点位气泡；切换后即时落盘。 */
    fun setPointsVisible(visible: Boolean) {
        postToMain {
            _profile.update { it.copy(pointsVisible = visible) }
            persistActiveAsync()
        }
    }

    private fun persistActiveAsync() {
        runCatching { repository?.save(_profile.value) }
            .onFailure { android.util.Log.w("TapRepo", "设置自动保存失败: ${it.message}") }
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** 需要同步拿到结果时使用；调用方应已在主线程或可接受短暂同步。 */
    private fun postToMainSync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
