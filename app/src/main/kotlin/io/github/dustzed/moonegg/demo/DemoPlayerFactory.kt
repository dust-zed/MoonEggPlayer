package io.github.dustzed.moonegg.demo

import android.content.res.AssetManager
import android.view.Surface
import io.github.dustzed.moonegg.bindings.NativePlayer
import io.github.dustzed.moonegg.player.NativeSurfaceBridge

internal fun createAvDemoPlayer(
    assets: AssetManager,
    surface: Surface,
): NativePlayer {
    return assets.openFd("demo.mp4").use { asset ->
        val fd: Int = asset.parcelFileDescriptor.fd
        val offset: Long = asset.startOffset
        val length: Long = asset.declaredLength

        require(length >= 0L) { "demo.mp4 长度未知" }

        // native 创建时复制 fd，退出 use 后可以关闭 asset。
        NativeSurfaceBridge.createAvPlayer(fd, offset, length, surface)
    }
}
