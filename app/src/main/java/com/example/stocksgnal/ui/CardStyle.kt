package com.example.stocksgnal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * 카드 테두리 색: 카드 역할마다 색을 달리해서 한눈에 구분되게 한다.
 * (앱 초기 단계라 구분을 뚜렷하게. 나중에 줄이려면 여기 값만 바꾸면 됨)
 */
object CardTone {
    val Data = Color(0xFF8A94A6)       // 데이터·갱신·안내
    val Action = Color(0xFF5B9BFF)     // 오늘 할 일·주문
    val Strategy = Color(0xFFF2B84B)   // 전략 상태·게이지
    val Ladder = Color(0xFF4FC3F7)     // 캠페인 단계
    val Recovery = Color(0xFFA98BFF)   // 1-3 조건
    val Sell = Color(0xFFFF6B6B)       // 매도
    val Budget = Color(0xFF3FBF7F)     // 예산
    val Schedule = Color(0xFF3CC6C6)   // 일정·캘린더
    val Warning = Color(0xFFFFA24B)    // 주의 문구
    val Account = Color(0xFFFF8FB8)    // 계좌
    val Settings = Color(0xFFB8C0D0)   // 설정
    val History = Color(0xFFC8B58A)    // 기록·이력
    val Short = Color(0xFF9CCC65)      // 단기전략
    val Chart = Color(0xFF6FA8DC)      // 차트
    val News = Color(0xFFE0A96D)       // 뉴스
}

/** 카드 테두리 (얇은 선) */
fun toneBorder(color: Color): BorderStroke = BorderStroke(1.dp, color.copy(alpha = 0.9f))
