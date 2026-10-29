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

package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.C139Constants
import com.yunx.app.data.network.model.ShareExpire
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 139 网盘云盘浏览 UI 状态 */
sealed interface C139CloudUiState {
    data object Loading : C139CloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        /** 当前目录 fileId（根="/"） */
        val dirId: String
    ) : C139CloudUiState
    data class Error(val message: String) : C139CloudUiState
}

/**
 * 139 网盘（和彩云）云盘浏览 ViewModel（参考百度/夸克云盘）：
 * - 目录浏览（根/子目录/面包屑回退）+ 下拉刷新
 * - 文件操作：下载 / 重命名 / 移动 / 创建分享 / 删除 + 长按多选批量
 * 认证走 Cookie（内部提取 authorization），目录用 fileId（根"/"），文件标识 fileId。
 */
class C139CloudViewModel(
    private val api: C139Api,
    private val cookieProvider: suspend () -> String?,
    private val downloadManager: DownloadManager,
    private val loginState: Flow<Boolean>
) : ViewModel() {

    private val _uiState = MutableStateFlow<C139CloudUiState>(C139CloudUiState.Loading)
    val uiState: StateFlow<C139CloudUiState> = _uiState.asStateFlow()

    var actionFile by mutableStateOf<ShareFile?>(null)
        private set
    var cloudMessage by mutableStateOf<String?>(null)
        private set
    var isOperating by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var shareResult by mutableStateOf<ShareInfo?>(null)
        private set
    var multiSelectMode by mutableStateOf(false)
        private set
    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    private val dirStack = ArrayDeque<String>()
    private val nameStack = ArrayDeque<String>()

    private val _moveUiState = MutableStateFlow<C139CloudUiState>(C139CloudUiState.Loading)
    val moveUiState: StateFlow<C139CloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    init {
        loadRoot()
        // 启动期未登录时上面的 loadRoot 会残留「请先登录…」错误态；登录态从无到有后自动重载根目录，
        // 进网盘列表无需再手动点「重试」。drop(1) 跳过 VM 创建时的登录态快照（init 已加载，避免冷启动重复），
        // distinctUntilChanged 过滤登录后 Cookie/Token 刷新等重复 upsert。
        viewModelScope.launch {
            loginState
                .drop(1)
                .distinctUntilChanged()
                .collect { loggedIn -> if (loggedIn) loadRoot() }
        }
    }

    private suspend fun cookie(): String =
        cookieProvider() ?: throw IllegalStateException("请先登录139网盘")

    // ---------- 目录浏览 ----------

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load("/", emptyList())
    }

    fun openFolder(file: ShareFile) {
        dirStack.addLast(file.fid)
        nameStack.addLast(file.fname)
        load(file.fid, nameStack.toList())
    }

    fun back() {
        if (nameStack.isEmpty()) {
            loadRoot()
            return
        }
        dirStack.removeLast()
        nameStack.removeLast()
        load(dirStack.lastOrNull() ?: "/", nameStack.toList())
    }

    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: "/", nameStack.toList())
    }

    // ---------- 多选 ----------

    fun enterMultiSelect(file: ShareFile) {
        multiSelectMode = true
        _selected.clear()
        _selected.add(file)
    }

    fun toggleSelect(file: ShareFile) {
        if (_selected.contains(file)) _selected.remove(file) else _selected.add(file)
    }

    fun toggleSelectAll(files: List<ShareFile>) {
        if (_selected.size == files.size) _selected.clear()
        else {
            _selected.clear()
            _selected.addAll(files)
        }
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        _selected.clear()
    }

    fun openActions(file: ShareFile) {
        actionFile = file
    }

    fun dismissActions() {
        actionFile = null
    }

    fun consumeMessage() {
        cloudMessage = null
    }

    fun dismissShareResult() {
        shareResult = null
    }

    // ---------- 移动目标浏览 ----------

    fun openMoveRoot() {
        moveDirStack.clear()
        moveNameStack.clear()
        moveLoad("/", emptyList())
    }

    fun openMoveFolder(file: ShareFile) {
        moveDirStack.addLast(file.fid)
        moveNameStack.addLast(file.fname)
        moveLoad(file.fid, moveNameStack.toList())
    }

    fun moveBack() {
        if (moveNameStack.isEmpty()) return
        moveDirStack.removeLast()
        moveNameStack.removeLast()
        moveLoad(moveDirStack.lastOrNull() ?: "/", moveNameStack.toList())
    }

    fun moveNavigateToLevel(level: Int) {
        while (moveNameStack.size > level) {
            moveDirStack.removeLast()
            moveNameStack.removeLast()
        }
        moveLoad(moveDirStack.lastOrNull() ?: "/", moveNameStack.toList())
    }

    private fun moveLoad(dirId: String, pathNames: List<String>) {
        _moveUiState.value = C139CloudUiState.Loading
        viewModelScope.launch {
            try {
                val files = api.listFolders(dirId, cookie())
                _moveUiState.value = C139CloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _moveUiState.value = C139CloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // ---------- 单文件操作 ----------

    fun renameFile(newName: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                api.renameFile(file.fid, newName, cookie())
                cloudMessage = "已重命名"
                actionFile = null
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "重命名失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 新建文件夹（当前目录下）；139 用 fileId，根目录沿用列目录/移动的 "/" 约定 */
    fun createFolder(name: String) {
        val newName = name.trim()
        if (newName.isEmpty()) return
        val parentId = (uiState.value as? C139CloudUiState.Loaded)?.dirId ?: "/"
        viewModelScope.launch {
            isOperating = true
            try {
                api.createDir(parentId, newName, cookie())
                cloudMessage = "已创建文件夹「$newName」"
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "新建文件夹失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 移动（异步任务 → 轮询） */
    fun moveFile(toDirId: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val taskId = api.moveFiles(listOf(file.fid), toDirId, cookie())
                    ?: throw IllegalStateException("移动失败")
                pollTask(taskId)
                cloudMessage = "已移动到目标目录"
                actionFile = null
                delay(1500)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    /**
     * 创建分享（139 提取码系统自动生成，仅选有效期）。
     *
     * @param expiredType UI 中性码（[ShareExpire]），**必须转成天数**：139 的 `period` 语义是
     *   天数，永久有效则完全不传该字段（见 `C139Api.createShare`）。
     */
    fun shareFile(expiredType: Int) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val coLst = if (file.isdir) emptyList() else listOf(file.fid)
                val caLst = if (file.isdir) listOf(file.fid) else emptyList()
                val info = api.createShare(coLst, caLst, ShareExpire.daysOrNull(expiredType), file.fname, cookie())
                shareResult = info
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 删除（异步任务 → 轮询） */
    fun deleteFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val taskId = api.deleteFiles(listOf(file.fid), cookie())
                    ?: throw IllegalStateException("删除失败")
                pollTask(taskId)
                cloudMessage = "已删除「${file.fname}」"
                actionFile = null
                delay(1200)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 批量操作 ----------

    fun shareSelected(expiredType: Int) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val coLst = files.filter { !it.isdir }.map { it.fid }
                val caLst = files.filter { it.isdir }.map { it.fid }
                val title = if (files.size == 1) files[0].fname else "分享 ${files.size} 个文件"
                val info = api.createShare(coLst, caLst, ShareExpire.daysOrNull(expiredType), title, cookie())
                shareResult = info
                exitMultiSelect()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 批量移动 */
    fun moveSelected(toDirId: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val taskId = api.moveFiles(files.map { it.fid }, toDirId, cookie())
                    ?: throw IllegalStateException("移动失败")
                pollTask(taskId)
                cloudMessage = "已移动 ${files.size} 项"
                exitMultiSelect()
                delay(1500)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 批量删除 */
    fun deleteSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val taskId = api.deleteFiles(files.map { it.fid }, cookie())
                    ?: throw IllegalStateException("删除失败")
                pollTask(taskId)
                cloudMessage = "已删除 ${files.size} 项"
                exitMultiSelect()
                delay(1200)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 内部 ----------

    /** 下拉刷新 */
    fun refresh() {
        val current = uiState.value
        if (current !is C139CloudUiState.Loaded) {
            loadRoot()
            return
        }
        refreshing = true
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(current.dirId, cookie())
                _uiState.value = C139CloudUiState.Loaded(files, current.pathNames, current.dirId)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    private fun reloadCurrent() {
        val current = uiState.value
        if (current is C139CloudUiState.Loaded) {
            load(current.dirId, current.pathNames)
        } else {
            loadRoot()
        }
    }

    private fun load(dirId: String, pathNames: List<String>) {
        _uiState.value = C139CloudUiState.Loading
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(dirId, cookie())
                _uiState.value = C139CloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _uiState.value = C139CloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    /** 轮询异步任务直到 Succeed（最长 ~30s） */
    private suspend fun pollTask(taskId: String) {
        delay(500)
        repeat(30) {
            val status = api.getTask(taskId, cookie())
            if (status.status == "Succeed" || status.progress >= 100) return
            if (status.results.any { it.second.isNotBlank() && it.second != "0000" }) {
                throw IllegalStateException("任务失败（${status.results.first().second}）")
            }
            delay(800)
        }
        throw IllegalStateException("任务超时")
    }

    class Factory(
        private val api: C139Api,
        private val cookieProvider: suspend () -> String?,
        private val downloadManager: DownloadManager,
        private val loginState: Flow<Boolean>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            C139CloudViewModel(api, cookieProvider, downloadManager, loginState) as T
    }
}