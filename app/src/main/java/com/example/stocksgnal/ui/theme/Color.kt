package com.example.stocksgnal.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/** 등락 색 (국내 관례): 상승·수익 = 빨강, 하락·손실 = 파랑. 0은 보합이라 색 없음 */
val UpRed = Color(0xFFFF4D4F)
val DownBlue = Color(0xFF4C8DFF)

/** 값의 부호에 맞는 등락 색. 0(보합)이면 Color.Unspecified → 기본 글자색 */
fun changeColor(v: Double?): Color = when {
    v == null || v == 0.0 -> Color.Unspecified
    v > 0 -> UpRed
    else -> DownBlue
}
