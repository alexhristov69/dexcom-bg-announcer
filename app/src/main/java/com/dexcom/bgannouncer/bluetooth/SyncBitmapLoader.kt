package com.dexcom.bgannouncer.bluetooth

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class SyncBitmapLoader(
    private val artworkProvider: () -> Bitmap?,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/")
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        artworkProvider()?.let { return Futures.immediateFuture(it) }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        return if (bitmap != null) {
            Futures.immediateFuture(bitmap)
        } else {
            Futures.immediateFailedFuture(IllegalStateException("Unable to decode artwork"))
        }
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        artworkProvider()?.let { return Futures.immediateFuture(it) }
        return Futures.immediateFailedFuture(IllegalStateException("No artwork available for $uri"))
    }

    override fun loadBitmapFromMetadata(metadata: androidx.media3.common.MediaMetadata): ListenableFuture<Bitmap> {
        artworkProvider()?.let { return Futures.immediateFuture(it) }
        metadata.artworkData?.let { data ->
            BitmapFactory.decodeByteArray(data, 0, data.size)?.let { bitmap ->
                return Futures.immediateFuture(bitmap)
            }
        }
        return Futures.immediateFailedFuture(IllegalStateException("No artwork available"))
    }
}
