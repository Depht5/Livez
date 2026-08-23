package dev.livefork.app.services

import dev.livefork.core.interfaces.LiveSite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 当前房间服务 —— 对应 Dart `CurrentRoomService`（lib/services/current_room_service.dart）。
 * 以 StateFlow 镜像 Dart 的 .obs 状态。
 */
class CurrentRoomService {

    private val _siteId = MutableStateFlow("")
    val siteId: StateFlow<String> = _siteId

    private val _roomId = MutableStateFlow("")
    val roomId: StateFlow<String> = _roomId

    /** 对应 Dart currentKey：siteId_roomId；任一为空则空串。 */
    val currentKey: String
        get() = if (_siteId.value.isEmpty() || _roomId.value.isEmpty()) {
            ""
        } else {
            "${_siteId.value}_${_roomId.value}"
        }

    fun setRoom(site: LiveSite, roomId: String) {
        _siteId.value = site.id
        _roomId.value = roomId
    }

    fun clearRoom() {
        _siteId.value = ""
        _roomId.value = ""
    }
}