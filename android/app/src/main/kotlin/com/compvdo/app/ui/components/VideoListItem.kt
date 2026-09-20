package com.compvdo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.compvdo.app.data.VideoInfo
import com.compvdo.app.util.FileSize
import com.compvdo.app.util.VideoPlayback

/**
 * One video in the list view.
 *
 * Three things here are deliberate, all from seeing this on a real device:
 *
 * 1. **The metadata is one non-wrapping line.** It was three separate `Text`s
 *    in a `Row`; once the duration ran long ("7m 8s") the resolution was
 *    squeezed into a sliver and wrapped character by character, rendering as
 *    "192 / 0×10 / 80" down three lines and making every row a different
 *    height. One string, `maxLines = 1`, `softWrap = false`.
 *
 * 2. **The thumbnail is a fixed landscape frame with `ContentScale.Fit`.**
 *    Cropping into a square threw away the sides of every 16:9 clip and made a
 *    portrait video look identical in shape to a landscape one. Fit
 *    letterboxes, so the frame shows the real orientation at a glance while the
 *    text still starts at the same x on every row.
 *
 * 3. **The estimated saving moved into the text column.** As a trailing
 *    `SuggestionChip` it competed with the filename for width, which is what
 *    left the name permanently truncated to "video_20260701_08…".
 *
 * The **whole card** toggles selection; previously only the checkbox did.
 */
@Composable
fun VideoListItem(
    video: VideoInfo,
    isSelected: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { onToggleSelection(!isSelected) },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isSelected,
                // The row owns the gesture; the box stays an indicator that
                // still works if someone aims at it precisely.
                onCheckedChange = onToggleSelection,
            )

            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(video.uri)
                        .decoderFactory(VideoFrameDecoder.Factory())
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOf(
                        video.formattedSize,
                        FileSize.formatDuration(video.duration),
                        FileSize.formatResolution(video.width, video.height),
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                if (video.estimatedSaving > 0) {
                    Text(
                        text = "~${FileSize.format(video.estimatedSaving)} to save",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Preview in whatever player the user already has (3b.5).
            IconButton(onClick = { VideoPlayback.open(context, video.uri, video.mimeType) }) {
                Icon(
                    Icons.Default.PlayCircleOutline,
                    contentDescription = "Preview ${video.displayName}",
                )
            }
        }
    }
}
