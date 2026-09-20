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
import com.compvdo.app.util.VideoPlayback
import com.compvdo.app.util.FileSize

/**
 * A card showing one video's info: thumbnail, name, size, duration, estimated
 * saving.
 *
 * The **entire card** toggles selection (3b.8c). Previously only the checkbox
 * responded, so tapping the filename — the obvious target — did nothing.
 */
@Composable
fun VideoListItem(
    video: VideoInfo,
    isSelected: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
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
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isSelected,
                // The row owns the gesture; the box is an indicator that still
                // works if someone aims at it precisely.
                onCheckedChange = onToggleSelection,
            )

            Spacer(Modifier.width(4.dp))

            // Small thumbnail — the "list view with a small thumbnail" mode.
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(video.uri)
                    .decoderFactory(VideoFrameDecoder.Factory())
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
            )

            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = video.formattedSize,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = FileSize.formatDuration(video.duration),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${video.width}×${video.height}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // 3b.5 — preview in whatever player the user already has.
            val context = LocalContext.current
            IconButton(onClick = { VideoPlayback.open(context, video.uri, video.mimeType) }) {
                Icon(
                    Icons.Default.PlayCircleOutline,
                    contentDescription = "Preview ${video.displayName}",
                )
            }

            // Estimated saving badge
            if (video.estimatedSaving > 0) {
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = "~${FileSize.format(video.estimatedSaving)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }
    }
}
