package com.yunx.app.data.network.video

/** Legacy backup platform identifiers. Names must remain stable. */
enum class VideoPlatform {
    DOUYIN, XHS, KUAISHOU, WEIBO, ACFUN, BILIBILI, PIXIV;

    companion object {
        fun fromName(name: String): VideoPlatform? = entries.firstOrNull { it.name == name }
    }
}
