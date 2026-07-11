package com.zhongshan.meterreader.util

import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 工业级资源池：线程安全 + 生命周期绑定 + 零GC复用
 * 互审结论最终版：修复ThreadLocal泄漏问题，并发场景无脏数据
 */
class BinarizeResourcePool : LifecycleObserver {

    private val isReleased = AtomicBoolean(false)

    // 每个分析线程独立缓冲区，无锁并发安全
    private val yBufferCache = ThreadLocal<ByteArray>()
    private val spriteBufferCache = ThreadLocal<ByteArray>()
    private val bitmapCache = ThreadLocal<Bitmap>()

    // 全局追踪所有已分配资源，销毁时统一释放
    private val allBuffers = Collections.synchronizedList(mutableListOf<ByteArray>())
    private val allBitmaps = Collections.synchronizedList(mutableListOf<Bitmap>())

    /** 获取Y通道缓冲区，自动扩容 */
    fun acquireYBuffer(capacity: Int): ByteArray {
        if (isReleased.get()) return ByteArray(capacity)
        val cache = yBufferCache.get()
        return if (cache != null && cache.size >= capacity) {
            cache
        } else {
            ByteArray(capacity).also {
                yBufferCache.set(it)
                allBuffers.add(it)
            }
        }
    }

    /** 获取Sprite画布缓冲区（ALPHA_8单通道），自动扩容 */
    fun acquireSpriteBuffer(capacity: Int): ByteArray {
        if (isReleased.get()) return ByteArray(capacity)
        val cache = spriteBufferCache.get()
        return if (cache != null && cache.size >= capacity) {
            cache
        } else {
            ByteArray(capacity).also {
                spriteBufferCache.set(it)
                allBuffers.add(it)
            }
        }
    }

    /** 获取ALPHA_8 Bitmap，尺寸变化时自动重建 */
    fun acquireBitmap(width: Int, height: Int): Bitmap {
        if (isReleased.get()) return Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val cache = bitmapCache.get()
        return if (cache != null && cache.width == width && cache.height == height && !cache.isRecycled) {
            cache
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also {
                bitmapCache.set(it)
                allBitmaps.add(it)
            }
        }
    }

    /** 页面销毁时统一释放所有资源，彻底杜绝内存泄漏 */
    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun release() {
        if (isReleased.compareAndSet(false, true)) {
            yBufferCache.remove()
            spriteBufferCache.remove()
            bitmapCache.remove()
            
            synchronized(allBitmaps) {
                allBitmaps.forEach { if (!it.isRecycled) it.recycle() }
                allBitmaps.clear()
            }
            synchronized(allBuffers) {
                allBuffers.clear()
            }
        }
    }
}
