package io.github.dustzed.moonegg.player

import android.view.Surface
import io.github.dustzed.moonegg.bindings.NativePlayer

object NativeSurfaceBridge {
    init {
        System.loadLibrary("moonegg_ffi")
    }

    private external fun nativeRegisterSurface(surface: Surface): Long

    private external fun nativeDiscardSurface(handle: Long)

    fun registerSurface(surface: Surface): Long {
        val handle = nativeRegisterSurface(surface)

        check(handle > 0)
        return handle
    }

    fun discardSurface(handle: Long) {
        nativeDiscardSurface(handle)
    }

    /**
     * 使用文件描述符和 Surface 创建音视频播放器。
     * 创建失败时，清理尚未领取的窗口句柄
     */
    fun createAvPlayer(
        fd: Int,
        offset: Long,
        length: Long,
        surface: Surface
    ): NativePlayer {
        check(surface.isValid)

        val handle: Long = registerSurface(surface)

        try {
            val player: NativePlayer = NativePlayer.fromAvFileDescriptor(
                fd, offset, length, handle
            )
            return player
        } catch (error: Throwable) {
            try {
                discardSurface(handle)
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }
}