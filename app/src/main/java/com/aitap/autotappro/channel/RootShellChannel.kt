package com.aitap.autotappro.channel

import android.util.Log
import com.aitap.autotappro.model.ChannelKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root 回放通道：通过 `su` 执行 shell `input` 命令。
 *
 * - 点击：`input tap x y`
 * - 滑动：`input swipe x1 y1 x2 y2 ms`
 * - Home/Back：`input keyevent KEYCODE_HOME / KEYCODE_BACK`
 *
 * 执行骨架复用 [RootHelper.runSuCommand]：优先 `su -c <cmd>`，失败兜底 `su 0 <cmd>`；
 * 读 exitCode/stdout/stderr，超时强杀。失败一律返回带退出码与 stderr 摘要的 [GestureOutcome.Failed]，
 * 由上层在悬浮层/Toast 回执，禁止静默吞掉。
 *
 * 注意：模拟器 toybox su 通常不支持 `-c`，本通道的实际 input 执行需在真机验证；
 * 模拟器仅能验证探测与选择逻辑。
 */
class RootShellChannel : GestureChannel {

    override val kind: ChannelKind = ChannelKind.ROOT

    private suspend fun exec(command: String): GestureOutcome = withContext(Dispatchers.IO) {
        val result = RootHelper.runSuCommand(command)
        if (result == null) {
            Log.w("TapRoot", "Root 命令启动失败/超时: $command")
            return@withContext GestureOutcome.Failed("su 进程启动失败或执行超时: $command")
        }
        Log.i("TapRoot", "Root 命令 exit=${result.exitCode} stdout='${result.stdout.trim()}' stderr='${result.stderr.trim()}' cmd=$command")
        if (result.exitCode == 0) {
            GestureOutcome.Completed
        } else {
            val detail = (result.stderr.trim().ifEmpty { result.stdout.trim() }).take(120)
            GestureOutcome.Failed("Root 命令失败 exit=${result.exitCode} $detail".trim())
        }
    }

    override suspend fun tap(x: Int, y: Int, durationMs: Long): GestureOutcome =
        exec("input tap $x $y")

    override suspend fun swipe(
        sx: Int, sy: Int, ex: Int, ey: Int, durationMs: Long
    ): GestureOutcome =
        exec("input swipe $sx $sy $ex $ey ${durationMs.coerceAtLeast(1L)}")

    override suspend fun goHome(): GestureOutcome =
        exec("input keyevent KEYCODE_HOME")

    override suspend fun goBack(): GestureOutcome =
        exec("input keyevent KEYCODE_BACK")
}
