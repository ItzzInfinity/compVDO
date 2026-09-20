package com.compvdo.app.data

/**
 * A folder ("bucket"/album) of videos, as MediaStore groups them.
 *
 * This exists so a later UI task can render a grid of folder tiles — thumbnail,
 * name, count — without re-querying MediaStore. It carries no UI state.
 *
 * `bucketId` is MediaStore's `BUCKET_ID`, which is the lower-cased parent
 * directory path's `hashCode()`. When the column is unavailable we recompute it
 * the same way (see [MediaScanner.bucketIdFor]) so the two agree.
 */
data class VideoFolder(
    val bucketId: Long,
    /** e.g. "Camera", "Download", "WhatsApp Video", "WhatsApp Documents" */
    val displayName: String,
    /** e.g. "DCIM/Camera/" — may be blank on SAF-sourced folders */
    val relativePath: String,
    val videoCount: Int,
    val totalSize: Long,
    /** Newest video in the folder — use its `uri` for the tile thumbnail. */
    val representative: VideoInfo,
    /** Sum of the per-file estimates (R10.4: an estimate, never a promise). */
    val estimatedSaving: Long,
    /** True when the folder was reached through a SAF tree grant, not MediaStore. */
    val fromDocumentTree: Boolean = false,
) {
    val formattedTotalSize: String
        get() = VideoInfo.formatFileSize(totalSize)
}

/**
 * Result of a full scan: the flat list (existing callers) plus the same items
 * grouped into folders (folder-grid callers). Both views describe one scan, so
 * a screen can switch between them without a second MediaStore pass.
 */
data class ScanResult(
    val videos: List<VideoInfo> = emptyList(),
    val folders: List<VideoFolder> = emptyList(),
    /**
     * Folders that exist on disk but could not be indexed (see
     * [MediaScanner.UNSCANNABLE_HINTS]); the UI may offer a SAF folder picker.
     */
    val needsFolderGrant: Boolean = false,
)
