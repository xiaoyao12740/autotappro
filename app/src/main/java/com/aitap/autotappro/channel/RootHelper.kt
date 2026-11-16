package com.aitap.autotappro.channel

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.aitap.autotappro.service.TapAccessibilityService
import java.util.concurrent.TimeUnit

/**
 * Root 辅助工具：Root 通道的命令执行骨架与探测。
 *
 *  - `su id` 探测是否真 Root（退出码 0 且输出含 uid=0）；
 *  - 可选：在已 Root 时把本服务写入系统无障碍启用列表并打开总开关（读-合并-写，不覆盖其他服务）；
 *  - [RootShellChannel] 复用本类执行 `input tap/swipe/keyevent` 回放命令。
 *
 * 命令执行：读退出码 / stdout / stderr，带超时，`su -c` 失败兜底 `su 0 <cmd>`。
 */
object RootHelper {

    private const val SU_TIMEOUT_MS = 15_000L

    data class SuResult(val exitCode: Int, val stdout: String, val stderr: String)

    private const val TAG = "TapRoot"

    /** 探测是否可用真 Root：退出码 0 且输出明确含 uid=0，避免被空壳 su / 授权弹窗蒙蔽。 */
    fun isRootAvailable(): Boolean {
        val result = runSuCommand("id")
        if (result == null) {
            Log.w(TAG, "Root 探测：su 进程启动失败或超时")
            return false
        }
        val combined = (result.stdout + result.stderr).trim()
        val ok = result.exitCode == 0 && Regex("uid=0\\b").containsMatchIn(combined)
        Log.i(TAG, "Root 探测 id exit=${result.exitCode} stdout='${result.stdout.trim()}' stderr='${result.stderr.trim()}' -> root=$ok")
        return ok
    }

    /**
     * Root 辅助：把本无障碍服务写入系统启用列表并打开总开关。
     * 成功返回 true；未 Root / 命令失败返回 false，由 UI 提示手动开启。
     */
    fun enableAccessibilityViaRoot(context: Context): Boolean {
        if (!isRootAvailable()) return false
        val component = TapAccessibilityService.selfComponentName(context)

        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val merged = (current.split(':').map { it.trim() }.filter { it.isNotEmpty() } + component)
            .distinctBy { it.lowercase() }
            .joinToString(":")

        val putServices = runSuCommand("settings put secure enabled_accessibility_services $merged")
        if (putServices == null || putServices.exitCode != 0) return false

        val putEnabled = runSuCommand("settings put secure accessibility_enabled 1")
        if (putEnabled == null || putEnabled.exitCode != 0) return false

        return TapAccessibilityService.isEnabledBySystem(context)
    }

    internal fun runSuCommand(command: String): SuResult? {
        val primary = runCatching {
            ProcessBuilder("su", "-c", command).start()
        }.getOrNull()

        val process = primary ?: runCatching {
            ProcessBuilder("su", "0", command).start()
        }.getOrNull() ?: return null

        // 并发排空 stdout/stderr，避免输出填满管道缓冲区导致子进程阻塞、waitFor 卡死。
        var stdout = ""
        var stderr = ""
        val outReader = Thread {
            stdout = runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
        }.apply { isDaemon = true }
        val errReader = Thread {
            stderr = runCatching { process.errorStream.bufferedReader().use { it.readText() } }.getOrDefault("")
        }.apply { isDaemon = true }
        outReader.start()
        errReader.start()

        val finished = runCatching {
            process.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) {
            process.destroyForcibly()
            return null
        }
        outReader.join(1_000)
        errReader.join(1_000)

        return SuResult(process.exitValue(), stdout, stderr)
    }
}
