package net.unraidcontrol.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage

@Composable
fun ContainerIcon(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconUrl: String? = null,
    serverBaseUrl: String = "",
) {
    val shape = RoundedCornerShape(size * 0.28f)
    val resolved = resolveIconUrl(iconUrl, serverBaseUrl)

    Box(modifier = modifier.size(size).clip(shape)) {
        if (resolved != null) {
            SubcomposeAsyncImage(
                model = resolved,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = { Initials(name = name, color = color, size = size) },
                error = { Initials(name = name, color = color, size = size) },
            )
        } else {
            Initials(name = name, color = color, size = size)
        }
    }
}

@Composable
private fun Initials(name: String, color: Color, size: Dp) {
    val initials = name
        .filter { it.isLetter() }
        .take(2)
        .uppercase()
        .ifEmpty { "??" }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    0f to color,
                    1f to color.copy(alpha = 0.8f),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.36f).sp,
            letterSpacing = (-0.02).sp,
        )
    }
}

/**
 * Unraid's iconUrl is sometimes absolute (`http://tower/state/.../foo.png`),
 * sometimes root-relative (`/state/.../foo.png`). Prepend the configured
 * server base URL for the relative case.
 */
private fun resolveIconUrl(raw: String?, base: String): String? {
    if (raw.isNullOrBlank()) return null
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
    if (base.isBlank()) return null
    val baseTrim = base.trimEnd('/')
    val pathTrim = if (raw.startsWith("/")) raw else "/$raw"
    return baseTrim + pathTrim
}
