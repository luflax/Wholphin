package com.github.damontecres.wholphin.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.AppColors

/**
 * The smallest fraction the progress bar will draw, so that a barely-started download is still
 * visible. Real payloads start well below 1%.
 */
private const val MIN_VISIBLE_PROGRESS = 0.015f

/**
 * A determinate progress bar
 *
 * `androidx.tv.material3` has no progress indicator, so this follows the same approach as the
 * played-percent bar on the item cards.
 */
@Composable
fun DownloadProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(progress, label = "download_progress")
    Box(
        modifier =
            modifier
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated.coerceIn(MIN_VISIBLE_PROGRESS, 1f))
                    .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}

/**
 * A small pill showing the state of a download or request
 */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(AppColors.TransparentBlack50, RoundedCornerShape(25))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}
