package com.example.stocksgnal.ui

import androidx.compose.runtime.Composable

/**
 * 예전 '실행' 탭 자리. 주문·체결 기록은 전략 › QLD 화면의 [실행] 탭으로,
 * 계좌 설정·체결 기록 목록은 [계좌] 메뉴로 옮김.
 */
@Composable
fun ExecutionScreen(resetKey: Int = 0, dataRevision: Int = 0, onStockClick: (String) -> Unit = {}) {
    AccountScreen(resetKey = resetKey, dataRevision = dataRevision, onStockClick = onStockClick)
}
