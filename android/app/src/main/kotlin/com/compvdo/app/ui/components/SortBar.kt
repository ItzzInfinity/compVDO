package com.compvdo.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.compvdo.app.R
import com.compvdo.app.data.MediaScanner

/**
 * Sort chips for the video list — implements R10.2.
 */
@Composable
fun SortBar(
    currentSort: MediaScanner.SortOrder,
    onSortChanged: (MediaScanner.SortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SortChip(
            label = stringResource(R.string.sort_by_size),
            selected = currentSort == MediaScanner.SortOrder.SIZE,
            onClick = { onSortChanged(MediaScanner.SortOrder.SIZE) },
        )
        SortChip(
            label = stringResource(R.string.sort_by_date),
            selected = currentSort == MediaScanner.SortOrder.DATE,
            onClick = { onSortChanged(MediaScanner.SortOrder.DATE) },
        )
        SortChip(
            label = stringResource(R.string.sort_by_name),
            selected = currentSort == MediaScanner.SortOrder.NAME,
            onClick = { onSortChanged(MediaScanner.SortOrder.NAME) },
        )
        SortChip(
            label = stringResource(R.string.sort_by_savings),
            selected = currentSort == MediaScanner.SortOrder.SAVINGS,
            onClick = { onSortChanged(MediaScanner.SortOrder.SAVINGS) },
        )
    }
}

@Composable
private fun SortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}
