package com.example.stocksgnal.model

data class WatchStock(
    val ticker: String,
    val name: String,
    val enabled: Boolean = true
)