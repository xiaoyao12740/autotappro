package com.aitap.autotappro.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.aitap.autotappro.R
import com.aitap.autotappro.channel.RootHelper
import com.aitap.autotappro.core.ClickerCoordinator
import com.aitap.autotappro.core.RunState
import com.aitap.autotappro.model.ChannelMode
import com.aitap.autotappro.model.ClickProfile
import com.aitap.autotappro.model.channelLabel
import com.aitap.autotappro.service.ClickerFloatingService
import com.aitap.autotappro.service.TapAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 主界面：三张权限卡片（悬浮窗 / 无障碍 / 电池优化）状态与跳转，
 * onResume 回刷；打开悬浮面板入口；当前配置保存 / 另存为。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var overlayStatus: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var profileName: TextView
    private lateinit var runStateText: TextView
    private lateinit var pointCount: TextView
    private lateinit var profileList: LinearLayout
    private lateinit var channelStatus: TextView

    private val uiScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayStatus = findViewById(R.id.overlay_status)
        a11yStatus = findViewById(R.id.a11y_status)
        batteryStatus = findViewById(R.id.battery_status)
        profileName = findViewById(R.id.profile_name)
        runStateText = findViewById(R.id.run_state)
        pointCount = findViewById(R.id.point_count)
        profileList = findViewById(R.id.profile_list_container)
        channelStatus = findViewById(R.id.channel_status)

        findViewById<Button>(R.id.btn_overlay).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.btn_a11y).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btn_battery).setOnClickListener { openBatterySettings() }
        findViewById<Button>(R.id.btn_open_panel).setOnClickListener { openPanel() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { saveActive() }
        findViewById<Button>(R.id.btn_save_as).setOnClickListener { saveAs() }
        findViewById<Button>(R.id.btn_channel).setOnClickListener { pickChannel() }

        uiScope.launch {
            ClickerCoordinator.profile.collect { refreshProfileInfo() }
        }
        uiScope.launch {
            ClickerCoordinator.runState.collect { refreshRunState(it) }
        }
        uiScope.launch {
            ClickerCoordinator.activeChannel.collect { refreshChannelStatus() }
        }
        uiScope.launch {
            ClickerCoordinator.profile.collect { refreshChannelStatus() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshProfileInfo()
        refreshRunState(ClickerCoordinator.runState.value)
        refreshProfileList()
        refreshChannelStatus()
    }

    // ---- 权限状态与跳转 ----

    private fun refreshPermissions() {
        val overlay = Settings.canDrawOverlays(this)
        overlayStatus.text = if (overlay) "已授予" else "未授予"

        val a11y = TapAccessibilityService.isEnabledBySystem(this)
        a11yStatus.text = if (a11y) "已开启" else "未开启"

        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        batteryStatus.text = if (ignoring) "已加入白名单" else "未加入"
    }

    private fun openOverlaySettings() {
        startActivity(Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ))
    }

    private fun openAccessibilitySettings() {
        // 先尝试 Root 辅助写入（读-合并-写，不覆盖其他服务）；失败则跳系统设置手动开。
        val ok = RootHelper.enableAccessibilityViaRoot(this)
        if (!ok) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        else Toast.makeText(this, "已通过 Root 开启无障碍，请返回确认", Toast.LENGTH_LONG).show()
    }

    private fun openBatterySettings() {
        startActivity(Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        ))
    }

    // ---- 面板与配置 ----

    private fun openPanel() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        // 通道在开始运行时解析：无障碍优先，不可用自动降级 Root；此处不再强制先开无障碍。
        ClickerFloatingService.start(this)
        Toast.makeText(this, "悬浮面板已打开", Toast.LENGTH_SHORT).show()
    }

    private fun pickChannel() {
        val modes = ChannelMode.values()
        val labels = modes.map { it.displayName }.toTypedArray()
        val current = ClickerCoordinator.channelMode()
        AlertDialog.Builder(this)
            .setTitle("选择回放通道")
            .setSingleChoiceItems(labels, modes.indexOf(current)) { dialog, which ->
                val picked = modes[which]
                ClickerCoordinator.setChannelMode(picked)
                Toast.makeText(this, "已切换：${picked.displayName}（下次开始生效）", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshChannelStatus() {
        val mode = ClickerCoordinator.channelMode()
        val kind = ClickerCoordinator.activeChannel.value
        channelStatus.text = channelLabel(mode, kind)
    }

    private fun saveActive() {
        ClickerCoordinator.saveActiveProfile()
        Toast.makeText(this, "已保存当前配置", Toast.LENGTH_SHORT).show()
        refreshProfileInfo()
        refreshProfileList()
    }

    private fun saveAs() {
        val input = EditText(this).apply {
            setText(ClickerCoordinator.profile.value.name)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("另存为新配置")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val result = ClickerCoordinator.saveActiveProfileAs(name)
                if (result) Toast.makeText(this, "已另存为：$name", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                refreshProfileInfo()
                refreshProfileList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshProfileInfo() {
        val p = ClickerCoordinator.profile.value
        profileName.text = p.name
        pointCount.text = "点位：${p.points.size} / 100"
    }

    /** 重新读取私有目录存档并渲染列表；保存 / 另存为 / 删除 / 返回界面后调用。 */
    private fun refreshProfileList() {
        profileList.removeAllViews()
        val profiles = ClickerCoordinator.listProfiles()
        if (profiles.isEmpty()) {
            val hint = TextView(this).apply {
                text = "暂无已存配置，点「保存」或「另存为」后在此出现"
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                val pad = (10 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            profileList.addView(hint)
            return
        }
        profiles.forEach { profile -> addProfileRow(profile) }
    }

    private fun addProfileRow(profile: ClickProfile) {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() }
        }

        val labelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameView = TextView(this).apply {
            text = profile.name
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
        }
        val metaView = TextView(this).apply {
            text = "点位 ${profile.points.size} / 100 · ${if (profile.runMode.name == "LOOP_UNTIL_STOP") "循环" else "单次"}"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
        }
        labelCol.addView(nameView); labelCol.addView(metaView)
        row.addView(labelCol)

        val loadBtn = Button(this).apply {
            text = "载入"
            textSize = 13f
            minWidth = 0; minHeight = 0
            setOnClickListener {
                if (ClickerCoordinator.loadProfile(profile.name)) {
                    Toast.makeText(this@MainActivity, "已载入：${profile.name}", Toast.LENGTH_SHORT).show()
                    refreshProfileInfo(); refreshProfileList()
                } else {
                    Toast.makeText(this@MainActivity, "载入失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val deleteBtn = Button(this).apply {
            text = "删除"
            textSize = 13f
            minWidth = 0; minHeight = 0
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("删除配置")
                    .setMessage("确定删除「${profile.name}」？")
                    .setPositiveButton("删除") { _, _ ->
                        ClickerCoordinator.deleteProfile(profile.name)
                        refreshProfileList()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        row.addView(loadBtn); row.addView(deleteBtn)
        profileList.addView(row)
    }

    private fun refreshRunState(state: RunState) {
        val text = when (state) {
            RunState.Idle -> "空闲"
            RunState.Running -> "运行中"
            RunState.Paused -> "已暂停"
            RunState.Completed -> "已完成"
            RunState.Failed -> "执行失败"
        }
        runStateText.text = "状态：$text"
    }
}
