package com.example.stocksgnal.data

/** 자동 재시도가 있는 조회 클라이언트는 검증된 조회 URL만 허용한다. */
internal fun isNamuReadOnlyPath(path: String): Boolean = path in setOf(
    "/n2/acctinfo",
    "/gbstock/inquiry/v1/balance",
    "/krstock/inquiry/v1/assetStatus",
    "/gbstock/inquiry/v1/dailyTransaction",
    "/gbstock/inquiry/v1/unexecuted",
    "/gbstock/inquiry/v1/periodPnl",
    "/gbstock/inquiry/v1/periodPnlDetail",
    "/gbstock/quote/v1/current",
    "/gbstock/quote/v1/period",
    "/gbstock/quote/v1/symbolIndexFxPeriod",
    "/gbfuture/quote/v1/current"
)
