package com.aitap.autotappro.core

import android.content.Context
import com.aitap.autotappro.model.ClickProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 点击配置本地存档：私有目录 `profiles/`，纯本地、无网络。
 * 每套配置一个 `<名称>.json`，`index.json` 记录名称数组；另存活动配置 id。
 */
class ProfileRepository(context: Context) {

    private val dir: File = File(context.filesDir, "profiles").apply { mkdirs() }
    private val indexFile: File = File(dir, "index.json")
    private val activeFile: File = File(dir, "active.json")

    /** 读取全部已存档配置（按名称排序）。 */
    fun loadAll(): List<ClickProfile> {
        if (!indexFile.exists()) return emptyList()
        val arr = runCatching { JSONArray(indexFile.readText()) }.getOrElse { JSONArray() }
        val out = mutableListOf<ClickProfile>()
        for (i in 0 until arr.length()) {
            val name = arr.getString(i)
            val f = File(dir, "$name.json")
            if (f.exists()) runCatching {
                out.add(ClickProfile.fromJson(JSONObject(f.readText())))
            }.onFailure { android.util.Log.w("TapRepo", "load profile [$name] failed: ${it.message}") }
        }
        return out.sortedBy { it.name }
    }

    /** 按名称加载；不存在返回 null。 */
    fun load(name: String): ClickProfile? =
        runCatching {
            val f = File(dir, "$name.json")
            if (!f.exists()) null else ClickProfile.fromJson(JSONObject(f.readText()))
        }.onFailure { android.util.Log.w("TapRepo", "load [$name] failed: ${it.message}") }.getOrNull()

    /** 保存（覆盖同名），并记为活动配置。 */
    fun save(profile: ClickProfile) {
        File(dir, "${profile.name}.json").writeText(profile.toJson().toString())
        activeFile.writeText(JSONObject().put("activeName", profile.name).toString())
        refreshIndex()
    }

    /**
     * 另存为异名副本：深拷贝点位与参数，以 [newName] 落盘；同名时按覆盖保存处理。
     * 同名校验、异名副本、索引更新都在仓库层完成，UI 只调接口。
     * @return 落盘后的配置（名称已改为 newName）
     */
    fun saveAs(profile: ClickProfile, newName: String): ClickProfile {
        val safeName = newName.ifBlank { "未命名配置" }
        val copy = profile.copy(
            name = safeName,
            points = profile.points.map { it.copy() }.toMutableList()
        )
        File(dir, "$safeName.json").writeText(copy.toJson().toString())
        activeFile.writeText(JSONObject().put("activeName", safeName).toString())
        refreshIndex()
        return copy
    }

    fun delete(name: String) {
        File(dir, "$name.json").delete()
        refreshIndex()
        val active = runCatching { JSONObject(activeFile.readText()).optString("activeName") }.getOrNull()
        if (active == name && activeFile.exists()) activeFile.delete()
    }

    fun exists(name: String): Boolean = File(dir, "$name.json").exists()

    /** 读取上次保存的活动配置；无则返回 null。 */
    fun loadActiveProfile(): ClickProfile? {
        val activeName = runCatching {
            if (!activeFile.exists()) null
            else JSONObject(activeFile.readText()).optString("activeName").ifBlank { null }
        }.getOrNull() ?: return null
        return load(activeName)
    }

    private fun refreshIndex() {
        val names = dir.listFiles { f -> f.extension == "json" && f.name != "index.json" && f.name != "active.json" }
            ?.map { it.nameWithoutExtension } ?: emptyList()
        indexFile.writeText(JSONArray(names).toString())
    }
}
