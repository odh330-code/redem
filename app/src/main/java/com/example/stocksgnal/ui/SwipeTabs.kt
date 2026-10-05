package com.example.stocksgnal.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** 화면 내부 탭만 전환한다. 자식의 차트·가로 목록이 사용한 제스처는 빼앗지 않는다. */
@Composable
fun Modifier.swipeTabs(selected: Int, count: Int, onSelect: (Int) -> Unit): Modifier {
    val current = rememberUpdatedState(selected)
    val callback = rememberUpdatedState(onSelect)
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    return pointerInput(count, threshold) {
        if (count < 2) return@pointerInput
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onHorizontalDrag = { _, amount -> distance += amount },
            onDragCancel = { distance = 0f },
            onDragEnd = {
                if (abs(distance) >= threshold) {
                    val next = (current.value + if (distance < 0) 1 else -1).coerceIn(0, count - 1)
                    if (next != current.value) callback.value(next)
                }
                distance = 0f
            }
        )
    }
}
