package com.example.stocksgnal.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.stocksgnal.R

object StockDesign {
    val ScreenPadding = 16.dp
    val CardPadding = 16.dp
    val CardRadius = 8.dp
    val CardGap = 10.dp
}

data class AppThemeSpec(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val ink: Color,
    val mutedInk: Color,
    val panel: Color,
    val panelStrong: Color,
    val panelStroke: Color,
    val wallpaperAlpha: Float
)

enum class AppPalette(val label: String, val primary: Color) {
    BLUE("블루", Color(0xFF4F8CFF)),
    EMERALD("에메랄드", Color(0xFF24A878)),
    VIOLET("바이올렛", Color(0xFF8B6FEA)),
    AMBER("앰버", Color(0xFFE6A23C))
}

enum class AppBackground(val label: String, val theme: AppThemeSpec) {
    SOLID(
        "기본",
        AppThemeSpec(
            primary = Color(0xFF8EBBFF),
            secondary = Color(0xFF9FD8FF),
            tertiary = Color(0xFFBDA6FF),
            backgroundTop = Color(0xFF08111D),
            backgroundBottom = Color(0xFF111827),
            ink = Color(0xFFF5F7FA),
            mutedInk = Color(0xFFB8C2D6),
            panel = Color(0x8A111924),
            panelStrong = Color(0xB3111924),
            panelStroke = Color(0x33D7E4FF),
            wallpaperAlpha = 0f
        )
    ),
    COUNTRY_FIELD(
        "그린 필드",
        AppThemeSpec(
            primary = Color(0xFFB9D86B),
            secondary = Color(0xFF77C9B3),
            tertiary = Color(0xFFFFD166),
            backgroundTop = Color(0xFF123B38),
            backgroundBottom = Color(0xFF071B1A),
            ink = Color(0xFFF4FFF3),
            mutedInk = Color(0xFFB9D5C6),
            panel = Color(0xB30B2825),
            panelStrong = Color(0xD90B2825),
            panelStroke = Color(0x66B9D86B),
            wallpaperAlpha = 0.72f
        )
    ),
    MIDNIGHT(
        "미드나이트",
        AppThemeSpec(
            primary = Color(0xFF6FA8FF),
            secondary = Color(0xFF8F7BFF),
            tertiary = Color(0xFF44D7B6),
            backgroundTop = Color(0xFF07101F),
            backgroundBottom = Color(0xFF101827),
            ink = Color(0xFFF6F8FF),
            mutedInk = Color(0xFFB7C4DE),
            panel = Color(0x8A0D1626),
            panelStrong = Color(0xB30D1626),
            panelStroke = Color(0x3D8EBBFF),
            wallpaperAlpha = 0f
        )
    ),
    AURORA(
        "오로라",
        AppThemeSpec(
            primary = Color(0xFF43E0B7),
            secondary = Color(0xFF58A6FF),
            tertiary = Color(0xFFB48CFF),
            backgroundTop = Color(0xFF061918),
            backgroundBottom = Color(0xFF121A36),
            ink = Color(0xFFF2FFF9),
            mutedInk = Color(0xFFAED7CC),
            panel = Color(0x8A0A211F),
            panelStrong = Color(0xB30A211F),
            panelStroke = Color(0x4043E0B7),
            wallpaperAlpha = 0f
        )
    ),
    SUNSET(
        "선셋",
        AppThemeSpec(
            primary = Color(0xFFFFB86B),
            secondary = Color(0xFFFF6F91),
            tertiary = Color(0xFF8EA7FF),
            backgroundTop = Color(0xFF221322),
            backgroundBottom = Color(0xFF13213A),
            ink = Color(0xFFFFF6EE),
            mutedInk = Color(0xFFE5B9B8),
            panel = Color(0x8A1F1725),
            panelStrong = Color(0xB31F1725),
            panelStroke = Color(0x45FFB86B),
            wallpaperAlpha = 0f
        )
    ),
    WALL_STREET(
        "월스트리트",
        AppThemeSpec(
            primary = Color(0xFFD7B66A),
            secondary = Color(0xFF8EBBFF),
            tertiary = Color(0xFF36D399),
            backgroundTop = Color(0xFF06090D),
            backgroundBottom = Color(0xFF10141A),
            ink = Color(0xFFFFF8E8),
            mutedInk = Color(0xFFD3C5A5),
            panel = Color(0x780B0F14),
            panelStrong = Color(0x990B0F14),
            panelStroke = Color(0x4DD7B66A),
            wallpaperAlpha = 0.82f
        )
    ),
    DATA_FLOOR(
        "마켓 플로어",
        AppThemeSpec(
            primary = Color(0xFF38E8FF),
            secondary = Color(0xFF65FFA3),
            tertiary = Color(0xFFFFD166),
            backgroundTop = Color(0xFF03131A),
            backgroundBottom = Color(0xFF071D22),
            ink = Color(0xFFEFFFFF),
            mutedInk = Color(0xFFA6DCE2),
            panel = Color(0x78061820),
            panelStrong = Color(0x99061820),
            panelStroke = Color(0x5538E8FF),
            wallpaperAlpha = 0.78f
        )
    ),
    BRONZE_BULL(
        "브론즈 불",
        AppThemeSpec(
            primary = Color(0xFFE1A15A),
            secondary = Color(0xFFFFD28A),
            tertiary = Color(0xFF75D6A6),
            backgroundTop = Color(0xFF120B06),
            backgroundBottom = Color(0xFF1A1712),
            ink = Color(0xFFFFF3E2),
            mutedInk = Color(0xFFD9BA93),
            panel = Color(0x78191008),
            panelStrong = Color(0x99191008),
            panelStroke = Color(0x55E1A15A),
            wallpaperAlpha = 0.82f
        )
    ),
    CHART_WAVE(
        "차트 웨이브",
        AppThemeSpec(
            primary = Color(0xFF38E8FF),
            secondary = Color(0xFF66D9FF),
            tertiary = Color(0xFF65FFA3),
            backgroundTop = Color(0xFF03111F),
            backgroundBottom = Color(0xFF020A14),
            ink = Color(0xFFEFFFFF),
            mutedInk = Color(0xFFA6CFE0),
            panel = Color(0x70061220),
            panelStrong = Color(0x92061220),
            panelStroke = Color(0x5538E8FF),
            wallpaperAlpha = 0.9f
        )
    ),
    NEON_RALLY(
        "네온 랠리",
        AppThemeSpec(
            primary = Color(0xFF38FFD5),
            secondary = Color(0xFF28B8FF),
            tertiary = Color(0xFFA2FFD8),
            backgroundTop = Color(0xFF031827),
            backgroundBottom = Color(0xFF02101B),
            ink = Color(0xFFEFFFFF),
            mutedInk = Color(0xFFA7DCD8),
            panel = Color(0x70071824),
            panelStrong = Color(0x92071824),
            panelStroke = Color(0x5538FFD5),
            wallpaperAlpha = 0.86f
        )
    ),
    WALL_SUNSET(
        "월가 선셋",
        AppThemeSpec(
            primary = Color(0xFFFFC36B),
            secondary = Color(0xFF8EBBFF),
            tertiary = Color(0xFF36D399),
            backgroundTop = Color(0xFF081726),
            backgroundBottom = Color(0xFF1A120C),
            ink = Color(0xFFFFF4E4),
            mutedInk = Color(0xFFE2C7A5),
            panel = Color(0x72110D0A),
            panelStrong = Color(0x96110D0A),
            panelStroke = Color(0x55FFC36B),
            wallpaperAlpha = 0.88f
        )
    ),
    BRIGHT_NYC(
        "브라이터 NYC",
        AppThemeSpec(
            primary = Color(0xFFFFC96B),
            secondary = Color(0xFF73B8FF),
            tertiary = Color(0xFFFFE2A8),
            backgroundTop = Color(0xFF17395A),
            backgroundBottom = Color(0xFF160F19),
            ink = Color(0xFFFFF7E8),
            mutedInk = Color(0xFFE4D2B3),
            panel = Color(0x6E10131A),
            panelStrong = Color(0x9010131A),
            panelStroke = Color(0x55FFC96B),
            wallpaperAlpha = 0.84f
        )
    ),
    NEON_STREET(
        "네온 스트리트",
        AppThemeSpec(
            primary = Color(0xFF22D3EE),
            secondary = Color(0xFFFF5B6E),
            tertiary = Color(0xFFFFB45C),
            backgroundTop = Color(0xFF050D1A),
            backgroundBottom = Color(0xFF03070D),
            ink = Color(0xFFEFFFFF),
            mutedInk = Color(0xFFAAC7D6),
            panel = Color(0x70070D16),
            panelStrong = Color(0x92070D16),
            panelStroke = Color(0x5522D3EE),
            wallpaperAlpha = 0.84f
        )
    ),
    BULL_MARKET(
        "불 마켓",
        AppThemeSpec(
            primary = Color(0xFFD7B66A),
            secondary = Color(0xFF38FFD5),
            tertiary = Color(0xFFFFE2A8),
            backgroundTop = Color(0xFF081018),
            backgroundBottom = Color(0xFF120D08),
            ink = Color(0xFFFFF5E6),
            mutedInk = Color(0xFFD8C1A0),
            panel = Color(0x70110D09),
            panelStrong = Color(0x92110D09),
            panelStroke = Color(0x55D7B66A),
            wallpaperAlpha = 0.86f
        )
    ),
    NIGHT_WALL(
        "나이트 월가",
        AppThemeSpec(
            primary = Color(0xFF38E8FF),
            secondary = Color(0xFFD7B66A),
            tertiary = Color(0xFF8EBBFF),
            backgroundTop = Color(0xFF03111F),
            backgroundBottom = Color(0xFF020712),
            ink = Color(0xFFEFFFFF),
            mutedInk = Color(0xFFA9C8D8),
            panel = Color(0x7008111C),
            panelStrong = Color(0x9208111C),
            panelStroke = Color(0x5538E8FF),
            wallpaperAlpha = 0.86f
        )
    ),
    SKYLINE_RALLY(
        "스카이라인 랠리",
        AppThemeSpec(
            primary = Color(0xFFB8C7FF),
            secondary = Color(0xFFFF8F73),
            tertiary = Color(0xFFFFD28A),
            backgroundTop = Color(0xFF101B3A),
            backgroundBottom = Color(0xFF120C16),
            ink = Color(0xFFFFF2F0),
            mutedInk = Color(0xFFD7C1D6),
            panel = Color(0x70110D18),
            panelStrong = Color(0x92110D18),
            panelStroke = Color(0x55B8C7FF),
            wallpaperAlpha = 0.84f
        )
    )
}

fun appColorScheme(darkMode: Boolean, palette: AppPalette, background: AppBackground): ColorScheme {
    val spec = background.theme
    val primary = if (background == AppBackground.SOLID) palette.primary else spec.primary
    return if (darkMode) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF06111F),
            primaryContainer = primary.copy(alpha = 0.28f),
            onPrimaryContainer = spec.ink,
            secondary = spec.secondary,
            onSecondary = Color(0xFF06111F),
            secondaryContainer = spec.secondary.copy(alpha = 0.18f),
            onSecondaryContainer = spec.ink,
            tertiary = spec.tertiary,
            onTertiary = Color(0xFF06111F),
            tertiaryContainer = spec.tertiary.copy(alpha = 0.16f),
            onTertiaryContainer = spec.ink,
            background = spec.backgroundTop,
            onBackground = spec.ink,
            surface = spec.panel,
            onSurface = spec.ink,
            surfaceVariant = spec.panelStrong,
            onSurfaceVariant = spec.mutedInk,
            surfaceTint = Color.Transparent,
            inverseSurface = spec.ink,
            inverseOnSurface = spec.backgroundBottom,
            error = Color(0xFFFF6B6B),
            onError = Color(0xFF210507),
            outline = spec.panelStroke,
            outlineVariant = spec.panelStroke.copy(alpha = 0.45f),
            scrim = Color(0xB3000000)
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = primary.copy(alpha = 0.18f),
            onPrimaryContainer = Color(0xFF0B1220),
            secondary = spec.secondary,
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = spec.secondary.copy(alpha = 0.14f),
            onSecondaryContainer = Color(0xFF0B1220),
            tertiary = spec.tertiary,
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = spec.tertiary.copy(alpha = 0.12f),
            onTertiaryContainer = Color(0xFF0B1220),
            background = Color(0xFFF5F7FA),
            onBackground = Color(0xFF0B1220),
            surface = Color(0xEFFFFFFF),
            onSurface = Color(0xFF0B1220),
            surfaceVariant = Color(0xFFE6EAF2),
            onSurfaceVariant = Color(0xFF354052),
            surfaceTint = Color.Transparent,
            inverseSurface = Color(0xFF111924),
            inverseOnSurface = spec.ink,
            error = Color(0xFFD92D20),
            onError = Color(0xFFFFFFFF),
            outline = Color(0x33465366),
            outlineVariant = Color(0x1F465366),
            scrim = Color(0x66000000)
        )
    }
}

@Composable
fun glassCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface
)

@Composable
fun StockSgnalTheme(
    darkTheme: Boolean,
    palette: AppPalette,
    background: AppBackground,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = appColorScheme(darkTheme, palette, background),
        typography = Typography,
        content = content
    )
}

@Composable
fun AppBackground(
    background: AppBackground,
    darkMode: Boolean,
    content: @Composable () -> Unit
) {
    val brush = when (background) {
        AppBackground.SOLID -> Brush.verticalGradient(
            listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)
        )
        AppBackground.COUNTRY_FIELD -> Brush.linearGradient(
            listOf(background.theme.backgroundTop, Color(0xFF1D5B4C), background.theme.backgroundBottom)
        )
        AppBackground.MIDNIGHT -> Brush.linearGradient(
            listOf(background.theme.backgroundTop, Color(0xFF172554), background.theme.backgroundBottom)
        )
        AppBackground.AURORA -> Brush.linearGradient(
            listOf(background.theme.backgroundTop, Color(0xFF123C3A), background.theme.backgroundBottom)
        )
        AppBackground.SUNSET -> if (darkMode) {
            Brush.linearGradient(listOf(background.theme.backgroundTop, Color(0xFF5B2745), background.theme.backgroundBottom))
        } else {
            Brush.linearGradient(listOf(Color(0xFFFFF2E6), Color(0xFFFEE2E2), Color(0xFFE0E7FF)))
        }
        AppBackground.WALL_STREET,
        AppBackground.DATA_FLOOR,
        AppBackground.BRONZE_BULL,
        AppBackground.CHART_WAVE,
        AppBackground.NEON_RALLY,
        AppBackground.WALL_SUNSET,
        AppBackground.BRIGHT_NYC,
        AppBackground.NEON_STREET,
        AppBackground.BULL_MARKET,
        AppBackground.NIGHT_WALL,
        AppBackground.SKYLINE_RALLY -> Brush.verticalGradient(
            listOf(background.theme.backgroundTop, background.theme.backgroundBottom)
        )
    }

    val imageResource = when (background) {
        AppBackground.WALL_STREET -> R.drawable.wall_street_night
        AppBackground.DATA_FLOOR -> R.drawable.market_data_floor
        AppBackground.BRONZE_BULL -> R.drawable.bronze_bull
        AppBackground.CHART_WAVE -> R.drawable.bg_chart_wave
        AppBackground.NEON_RALLY -> R.drawable.bg_neon_rally
        AppBackground.WALL_SUNSET -> R.drawable.bg_wall_sunset
        AppBackground.BRIGHT_NYC -> R.drawable.bg_bright_nyc
        AppBackground.NEON_STREET -> R.drawable.bg_neon_street
        AppBackground.BULL_MARKET -> R.drawable.bg_bull_market
        AppBackground.NIGHT_WALL -> R.drawable.bg_night_wall
        AppBackground.SKYLINE_RALLY -> R.drawable.bg_skyline_rally
        AppBackground.COUNTRY_FIELD -> R.drawable.bg_country_field
        else -> null
    }

    Box(
        modifier = Modifier.fillMaxSize().background(brush)
    ) {
        imageResource?.let { resource ->
            Image(
                painter = painterResource(resource),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = background.theme.wallpaperAlpha,
                modifier = Modifier.fillMaxSize()
            )
        }
        content()
    }
}
