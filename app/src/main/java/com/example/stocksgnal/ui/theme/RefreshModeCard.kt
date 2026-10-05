package com.example.stocksgnal.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stocksgnal.ui.theme.StockDesign
import com.example.stocksgnal.ui.theme.glassCardColors

@Composable
fun RefreshModeCard() {

    val context = LocalContext.current

    val preferences = remember {
        context.getSharedPreferences(
            "stock_signal_settings",
            Context.MODE_PRIVATE
        )
    }

    var refreshMode by remember {
        mutableStateOf(
            preferences.getString(
                "refresh_mode",
                "hourly"
            ) ?: "hourly"
        )
    }

    fun saveMode(mode: String) {
        refreshMode = mode

        preferences.edit()
            .putString(
                "refresh_mode",
                mode
            )
            .apply()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(StockDesign.CardRadius),
        colors = glassCardColors()
    ) {

        Column(
            modifier = Modifier.padding(StockDesign.CardPadding)
        ) {

            Text(
                text = "데이터 갱신",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(6.dp)
            )

            Text(
                text = "계좌·관심종목·전략 데이터를 갱신하는 방식을 선택합니다.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        saveMode("manual")
                    }
                    .padding(
                        vertical = 8.dp
                    )
            ) {

                RadioButton(
                    selected =
                        refreshMode == "manual",
                    onClick = {
                        saveMode("manual")
                    }
                )

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

                Column {

                    Text(
                        text = "수동 새로고침만",
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "홈의 새로고침 버튼을 눌렀을 때만 갱신",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        saveMode("hourly")
                    }
                    .padding(
                        vertical = 8.dp
                    )
            ) {

                RadioButton(
                    selected =
                        refreshMode == "hourly",
                    onClick = {
                        saveMode("hourly")
                    }
                )

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

                Column {

                    Text(
                        text = "1시간마다 자동 갱신",
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "앱 사용 중 마지막 갱신에서 1시간이 지나면 자동 갱신 · 수동 버튼도 사용 가능",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
