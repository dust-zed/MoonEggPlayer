package io.github.dustzed.moonegg.player

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 提供视频输出窗口及其声明周期通知。
 */
class VideoSurfaceView(
    context: Context
) : SurfaceView(context), SurfaceHolder.Callback {

    // 窗口创建后通知调用方
    var onSurfaceReady: ((Surface) -> Unit)? = null

    // 窗口销毁前通知调用方。
    var onSurfaceLost: (() -> Unit)? = null
    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {

    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface = holder.surface
        onSurfaceReady?.invoke(surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        onSurfaceLost?.invoke()
    }

    init {
        holder.addCallback(this)
    }

}