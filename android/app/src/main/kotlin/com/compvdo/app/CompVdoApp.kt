package com.compvdo.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Application class.
 *
 * Owns:   the process-wide Coil image loader.
 * Reads:  nothing.
 * Writes: a thumbnail disk cache under the app's own cache directory.
 * Runs:   nothing.
 *
 * Thumbnails are the expensive part of this UI, not the encoding. Every tile
 * asks `MediaMetadataRetriever` for a frame, which means seeking into a video
 * that may be 4K and hundreds of megabytes — on a 300-video library that is
 * hundreds of full-resolution decodes, repeated every time the grid is drawn.
 *
 * Three things make it bearable, and they belong here rather than at each call
 * site so no screen can forget them:
 *
 *  - a **disk cache**, so a frame is decoded once per file ever, not once per
 *    scroll. This is by far the biggest win and Coil does not enable one for
 *    video frames by default in a way that survives process death.
 *  - a **memory cache** sized to a share of the heap, for scrolling back up.
 *  - `VideoFrameDecoder` registered once, globally.
 *
 * Requests still pass their own `size(...)`; decoding a 3840x2160 frame to fill
 * a 64dp box is the other half of the cost.
 */
class CompVdoApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumbnails"))
                    .maxSizeBytes(96L * 1024 * 1024)
                    .build()
            }
            // RGB_565 halves the bytes per thumbnail. At 64dp there is nothing
            // to see in the extra channel depth, and the grid holds a lot of
            // them at once.
            .allowRgb565(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            .crossfade(true)
            .build()
}
