/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.data.announcement

import android.util.Log
import com.yunx.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** 从公开 GitHub 仓库的 JSON 文件读取公告。 */
object AnnouncementApi {
    const val BASE_URL = "https://raw.githubusercontent.com/yzq313/FPAN-APP/master/announcements.json"
    const val PAGE_SIZE = 100

    /** 公告相关日志统一走这个标签，失败原因一律 E 级打印，方便直接看 logcat 定位 */
    private const val TAG = "YunX-Announce"

    /** 发布者（服务端 `publisher` 对象） */
    data class Publisher(val name: String, val avatarUrl: String?)

    /**
     * 公告对象（公开字段，见接口文档 §2.4）。
     * [content] 只有详情接口返回；[coverImage] / [images] / [publisher.avatarUrl] 都是**完整直链**，可直接加载。
     */
    data class Announcement(
        val id: String,
        val title: String,
        val summary: String,
        val content: String?,
        val coverImage: String?,
        val images: List<String>,
        val publishAt: String?,
        val author: String,
        val viewCount: Long,
        val publisher: Publisher,
        val isPinned: Boolean,
        val pinnedAt: String?,
        val pinExpireAt: String?,
        val sortOrder: Int,
        val createdAt: String,
        val updatedAt: String,
    ) {
        /** 生效时间（毫秒）：`publishAt` 为空表示「立即发布」，退回 createdAt；两者都解析不出来才是 0 */
        val effectiveMillis: Long get() = parseIsoMillis(publishAt) ?: parseIsoMillis(createdAt) ?: 0L
    }

    /** 分页结果（接口文档 §1.3）：`hasMore` 等价于 `page * pageSize < total` */
    data class Page(
        val total: Int,
        val page: Int,
        val pageSize: Int,
        val totalPages: Int,
        val hasMore: Boolean,
        val list: List<Announcement>,
    )

    /** 请求结果：失败带上可直接展示的原因（服务端 `message` / HTTP 码 / 异常信息） */
    sealed interface Result<out T> {
        data class Success<T>(val data: T) : Result<T>
        data class Failure(val message: String) : Result<Nothing>
    }

    /** 读取静态文件并在客户端分页；未到发布时间的公告不会显示。 */
    suspend fun fetchPage(page: Int = 1, pageSize: Int = PAGE_SIZE): Result<Page> =
        when (val result = fetchAnnouncements()) {
            is Result.Failure -> result
            is Result.Success -> {
                val safePage = page.coerceAtLeast(1)
                val safeSize = pageSize.coerceIn(1, PAGE_SIZE)
                val all = result.data
                val start = ((safePage.toLong() - 1) * safeSize).coerceAtMost(all.size.toLong()).toInt()
                val end = (start + safeSize).coerceAtMost(all.size)
                Result.Success(Page(all.size, safePage, safeSize,
                    (all.size + safeSize - 1) / safeSize, end < all.size, all.subList(start, end)))
            }
        }

    /** 静态公告没有浏览量统计。 */
    suspend fun fetchDetail(id: String): Result<Announcement> =
        when (val result = fetchAnnouncements()) {
            is Result.Failure -> result
            is Result.Success -> result.data.firstOrNull { it.id == id.trim() }
                ?.let { Result.Success(it) } ?: Result.Failure("公告不存在或尚未发布")
        }

    private suspend fun fetchAnnouncements(): Result<List<Announcement>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(BASE_URL)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "FPAN-APP").get().build()
            HttpClients.apiClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.Failure("HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                val array = JSONObject(body).getJSONArray("announcements")
                val now = System.currentTimeMillis()
                val items = parseAnnouncementArray(array)
                    .distinctBy { it.id }
                    .filter { it.publishAt == null || (parseIsoMillis(it.publishAt)?.let { time -> time <= now } == true) }
                    .sortedWith(compareByDescending<Announcement> { it.isPinned }
                        .thenByDescending { it.sortOrder }.thenByDescending { it.effectiveMillis })
                Result.Success(items)
            }
        } catch (e: Exception) {
            Log.e(TAG, "公告加载失败", e)
            Result.Failure("公告加载失败：${e.message ?: "网络或数据异常"}")
        }
    }

    private fun parseAnnouncementArray(arr: JSONArray): List<Announcement> {
        val out = ArrayList<Announcement>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val parsed = parseAnnouncement(item)
            // ★ id 是列表 key（LazyColumn 的 key 必须唯一）与已读记录的键：空 id 直接丢掉，
            //   否则一条脏数据就会让整个列表抛 "Key was already used"。
            if (parsed.id.isEmpty()) {
                Log.w(TAG, "公告列表第 $i 条缺少 id，已跳过（title=${parsed.title.take(30)}）")
                continue
            }
            out.add(parsed)
        }
        return out
    }

    private fun parseStringArray(arr: JSONArray): List<String> {
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            if (arr.isNull(i)) continue
            val text = arr.optString(i).trim()
            if (text.isNotEmpty()) out.add(text)
        }
        return out
    }

    private fun parseAnnouncement(json: JSONObject): Announcement = Announcement(
        id = json.stringOrNull("id").orEmpty(),
        title = json.stringOrNull("title").orEmpty(),
        summary = json.stringOrNull("summary").orEmpty(),
        content = json.stringOrNull("content"),
        coverImage = json.stringOrNull("coverImage"),
        images = json.optJSONArray("images")?.let { parseStringArray(it) } ?: emptyList(),
        publishAt = json.stringOrNull("publishAt"),
        author = json.stringOrNull("author").orEmpty(),
        viewCount = json.optLong("viewCount"),
        publisher = json.optJSONObject("publisher")?.let { p ->
            Publisher(
                name = p.stringOrNull("name").orEmpty(),
                avatarUrl = p.stringOrNull("avatarUrl")
            )
        } ?: Publisher(name = "", avatarUrl = null),
        isPinned = json.optBoolean("isPinned"),
        pinnedAt = json.stringOrNull("pinnedAt"),
        pinExpireAt = json.stringOrNull("pinExpireAt"),
        sortOrder = json.optInt("sortOrder"),
        createdAt = json.stringOrNull("createdAt").orEmpty(),
        updatedAt = json.stringOrNull("updatedAt").orEmpty()
    )


}

/**
 * `optString` 遇到 JSON null 会返回字符串 `"null"`（不是空串），可空字段一律走这里。
 * 「字段缺失」与「字段为 null」在接口里是两种含义（如 publishAt = null 表示立即发布），
 * 所以这里只做「取不到 / 为 null ⇒ null」，空串也算没值（避免把 "" 当时间/图片地址用）。
 */
private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
