// 文件名: BinarizeResourcePool.kt
package com.zhongshan.meterreader.util

import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import java.util.concurrent.atomic.AtomicReference

class BinarizeResourcePool : LifecycleObserver {

    val yBufferRef: AtomicReference<ByteArray?> = AtomicReference(null)
    // 修改：画布缓冲改为 IntArray 以支持 ARGB_8888
    val canvasBufferRef: AtomicReference<IntArray?> = AtomicReference(null)
    val bitmapRef: AtomicReference<Bitmap?> = AtomicReference(null)

    fun acquireYBuffer(size: Int): ByteArray {
        var buffer = yBufferRef.get()
        if (buffer == null || buffer.size < size) {
            buffer = ByteArray(size)
            yBufferRef.set(buffer)
        }
        return buffer
    }

    fun acquireCanvasBuffer(size: Int): IntArray {
        var buffer = canvasBufferRef.get()
        if (buffer == null || buffer.size < size) {
            buffer = IntArray(size)
            canvasBufferRef.set(buffer)
        }
        return buffer
    }

    fun acquireBitmap(width: Int, height: Int): Bitmap {
        var bitmap = bitmapRef.get()
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            bitmap?.recycle()
            // 修改：强制使用 ARGB_8888，ML Kit 不支持 ALPHA_8
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmapRef.set(bitmap)
        }
        return bitmap
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        yBufferRef.set(null)
        canvasBufferRef.set(null)
        bitmapRef.get()?.recycle()
        bitmapRef.set(null)
    }
}
