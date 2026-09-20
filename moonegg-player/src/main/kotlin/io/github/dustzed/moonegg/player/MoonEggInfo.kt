package io.github.dustzed.moonegg.player

import io.github.dustzed.moonegg.bindings.coreVersion

object MoonEggInfo {
    fun description(): String = "MoonEgg Rust Core: ${coreVersion()}"
}