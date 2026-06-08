package dev.weixiao.wxfilemanager.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Activity 级共享 ViewModel，用于 [dev.weixiao.wxfilemanager.MainActivity] 顶栏按钮
 * 与子 Fragment（LocalFragment / SmbFragment）之间的事件通信。
 *
 * 取代原先通过 `childFragmentManager.fragments.firstOrNull()` 强转 Fragment 调方法的硬耦合写法。
 */
class MainViewModel : ViewModel() {

    enum class UiEvent { OpenSearch, OpenViewSettings }

    /** extraBufferCapacity=1 + DROP_OLDEST：避免连续点击造成的事件堆积 */
    private val _events = MutableSharedFlow<UiEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun emit(event: UiEvent) {
        viewModelScope.launch { _events.emit(event) }
    }
}
