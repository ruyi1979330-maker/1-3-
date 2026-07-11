// 文件名: BinarizeResourcePool.kt
package com.zhongshan.meterreader.util

import android.graphics.Bitmap
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

class BinarizeResourcePool : DefaultLifecycleObserver {
    private val yuvBuffer = AtomicReference<ByteArray>(null)
    private val canvasBuffer = AtomicReference<ByteArray>(null)
    
    // 引入双缓冲机制，防止 ML Kit 异步读取时发生帧撕裂
    private val reusedBitmaps = AtomicReferenceArray<Bitmap>(2)
    private val bitmapIndex = AtomicInteger(0)

    fun acquireYuvBuffer(size: Int): ByteArray {
        var buffer = yuvBuffer.get()
        if (buffer == null || buffer.size < size) {
            buffer = ByteArray(size)
            yuvBuffer.set(buffer)
        }
        return buffer
    }

    fun acquireCanvasBuffer(size: Int): ByteArray {
        var buffer = canvasBuffer.get()
        if (buffer == null || buffer.size < size) {
            buffer = ByteArray(size)
            canvasBuffer.set(buffer)
        }
        return buffer
    }

    fun acquireBitmap(width: Int, height: Int): Bitmap {
        val currentIndex = bitmapIndex.getAndUpdate { (it + 1) % 2 }
        var bmp = reusedBitmaps.get(currentIndex)
        
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            reusedBitmaps.set(currentIndex, bmp)
        }
        return bmp
    }

    override fun onDestroy(owner: LifecycleOwner) {
        yuvBuffer.set(null)
        canvasBuffer.set(null)
        reusedBitmaps.getAndSet(0, null)?.recycle()
        reusedBitmaps.getAndSet(1, null)?.recycle()
        super.onDestroy(owner)
    }
}
