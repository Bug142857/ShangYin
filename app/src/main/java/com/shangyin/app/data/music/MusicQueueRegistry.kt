package com.shangyin.app.data.music

import java.util.concurrent.ConcurrentHashMap

/**
 * 播放队列歌曲登记表。
 *
 * ExoPlayer 的 MediaItem 里只能塞歌名/歌手/封面这类展示字段，而音源解析直链还需要
 * 平台原始字段（raw），所以播放前把整份队列按 key 存一份，播放时由数据源取回。
 * 仅存活于当前进程会话（不持久化，重启后重新播放时再登记）。
 */
object MusicQueueRegistry {

    private val songs = ConcurrentHashMap<String, MusicSong>()

    fun register(list: List<MusicSong>) {
        list.forEach { songs[it.key] = it }
    }

    fun get(key: String): MusicSong? = songs[key]
}
