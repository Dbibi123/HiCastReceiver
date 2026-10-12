package com.example.hicastreceiver

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

object QualityCapabilities {
    data class Report(
        val supports4k30: Boolean,
        val hardwareCodecs: List<String>,
        val supported4kTypes: List<String>,
        val summary: String,
    )

    fun detect(): Report {
        val codecs = mutableListOf<String>()
        val types4k = linkedSetOf<String>()
        val mimeTypes = listOf(
            "video/hevc" to "HEVC/H.265",
            "video/avc" to "AVC/H.264",
            "video/x-vnd.on2.vp9" to "VP9",
            "video/av01" to "AV1",
        )

        try {
            for (codec in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
                if (codec.isEncoder) continue
                val hardware = if (Build.VERSION.SDK_INT >= 29) {
                    codec.isHardwareAccelerated && !codec.isSoftwareOnly
                } else {
                    !codec.name.contains("google", true) &&
                        !codec.name.contains("software", true) &&
                        !codec.name.contains("sw", true)
                }
                if (!hardware) continue
                var codecSupports4k = false
                for ((mime, label) in mimeTypes) {
                    if (!codec.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
                    val caps = try { codec.getCapabilitiesForType(mime) } catch (_: Exception) { continue }
                    val video = caps.videoCapabilities ?: continue
                    val supports = try { video.areSizeAndRateSupported(3840, 2160, 30.0) } catch (_: Exception) { false }
                    if (supports) {
                        types4k += label
                        codecSupports4k = true
                    }
                }
                if (codecSupports4k) codecs += codec.name
            }
        } catch (_: Exception) {
            // The UI will report an unknown capability rather than treating it as 4K-ready.
        }

        val supports4k = types4k.isNotEmpty()
        val summary = when {
            supports4k -> "硬件解码器报告支持 4K@30：${types4k.joinToString(", ")}。4K@60/HDR 需单独实测。"
            else -> "未检测到明确支持 3840×2160@30 的硬件解码器；需核对海思 Codec 配置与系统媒体能力。"
        }
        return Report(supports4k, codecs.distinct(), types4k.toList(), summary)
    }
}
