	// 文件名: BinarizeResourcePool.kt
	package com.zhongshan.meterreader.util
	import android.graphics.Bitmap
	import androidx.lifecycle.Lifecycle
	import androidx.lifecycle.LifecycleObserver
	import androidx.lifecycle.OnLifecycleEvent
	import java.util.concurrent.atomic.AtomicReference
	class BinarizeResourcePool : LifecycleObserver {
	    // YUV 的 Y 通道拷贝缓冲
	    val yBufferRef: AtomicReference<ByteArray?> = AtomicReference(null)
	    // ALPHA_8 拼接画布缓冲
	    val canvasBufferRef: AtomicReference<ByteArray?> = AtomicReference(null)
	    // 复用的 ALPHA_8 Bitmap
	    val bitmapRef: AtomicReference<Bitmap?> = AtomicReference(null)
	    fun acquireYBuffer(size: Int): ByteArray {
	        var buffer = yBufferRef.get()
	        if (buffer == null || buffer.size < size) {
	            buffer = ByteArray(size)
	            yBufferRef.set(buffer)
	        }
	        return buffer
	    }
	    fun acquireCanvasBuffer(size: Int): ByteArray {
	        var buffer = canvasBufferRef.get()
	        if (buffer == null || buffer.size < size) {
	            buffer = ByteArray(size)
	            canvasBufferRef.set(buffer)
	        }
	        return buffer
	    }
	    fun acquireBitmap(width: Int, height: Int): Bitmap {
	        var bitmap = bitmapRef.get()
	        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
	            bitmap?.recycle()
	            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
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
