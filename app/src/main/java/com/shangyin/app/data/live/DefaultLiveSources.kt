package com.shangyin.app.data.live

/**
 * 内置默认电视源（M3U IPTV 列表）。
 * 仅在「电视源配置」从未配置过时播种一次（SettingsStore.ensureDefaultLiveSourcesSeeded）。
 * 走 gh-proxy 加速（国内直连 raw.githubusercontent 常失败）。
 */
val DEFAULT_LIVE_SOURCES: List<LiveSource> = listOf(
    LiveSource(
        id = "iptv4",
        name = "IPv4 直播源",
        url = "https://gh-proxy.com/raw.githubusercontent.com/vbskycn/iptv/refs/heads/master/tv/iptv4.m3u"
    )
)
