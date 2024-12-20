package com.lizongying.mytv0.models

import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.data.Program
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.data.TV
import kotlin.math.max
import kotlin.math.min

class TVModel(var tv: TV) : ViewModel() {
    private val _position = MutableLiveData<Int>()
    val position: LiveData<Int>
        get() = _position

    var retryTimes = 0
    var retryMaxTimes = 10
    var programUpdateTime = 0L

    private var _groupIndex = 0
    val groupIndex: Int
        get() = if (SP.showAllChannels || _groupIndex == 0) _groupIndex else _groupIndex - 1

    fun setGroupIndex(index: Int) {
        _groupIndex = index
    }

    fun getGroupIndexInAll(): Int {
        return _groupIndex
    }

    var listIndex = 0

    private var sources: MutableList<SourceType> =
        mutableListOf(
            SourceType.UNKNOWN,
        )
    private var sourceIndex = -1

    private val _errInfo = MutableLiveData<String>()
    val errInfo: LiveData<String>
        get() = _errInfo

    fun setErrInfo(info: String) {
        _errInfo.value = info
    }

    private var _epg = MutableLiveData<MutableList<EPG>>()
    val epg: LiveData<MutableList<EPG>>
        get() = _epg

    fun setEpg(epg: MutableList<EPG>) {
        _epg.value = epg
    }

    private var _program = MutableLiveData<MutableList<Program>>()
    val program: LiveData<MutableList<Program>>
        get() = _program

    private fun getVideoUrl(): String? {
        if (_videoIndex.value == null || tv.uris.isEmpty()) {
            return null
        }

        if (videoIndex.value!! >= tv.uris.size) {
            return null
        }

        val index = min(max(_videoIndex.value!!, 0), tv.uris.size - 1)
        return tv.uris[index]
    }

    private val _like = MutableLiveData<Boolean>()
    val like: LiveData<Boolean>
        get() = _like

    fun setLike(liked: Boolean) {
        _like.value = liked
    }

    private val _ready = MutableLiveData<Boolean>()
    val ready: LiveData<Boolean>
        get() = _ready

    fun setReady() {
//        _videoIndex.value = (_videoIndex.value!! + 1) % tv.uris.size
//        if (tv.uris.size < 2) {
//            _videoIndex.value = 0
//        } else {
//            _videoIndex.value = Random.nextInt(0, tv.uris.size - 1)
//        }
        _ready.value = true
    }

    private val _videoIndex = MutableLiveData<Int>()
    private val videoIndex: LiveData<Int>
        get() = _videoIndex

    private var userAgent = ""

    // TODO Maybe _mediaItem has not been initialized when play
    private lateinit var _mediaItem: MediaItem

    fun getMediaItem(): MediaItem? {
        if (::_mediaItem.isInitialized) {
            return _mediaItem
        } else {
            getVideoUrl()?.let {
                _mediaItem = MediaItem.fromUri(it)
                return _mediaItem
            }

            return null
        }
    }

    private lateinit var httpDataSource: DataSource.Factory

    init {
        _position.value = 0
        _videoIndex.value = max(0, tv.uris.size - 1)
        _like.value = SP.getLike(tv.id)
        _program.value = mutableListOf()

        buildSource()
    }

    fun update(t: TV) {
        tv = t
    }

    @OptIn(UnstableApi::class)
    fun buildSource() {
        val url = getVideoUrl() ?: return
        val uri = Uri.parse(url) ?: return
        val path = uri.path ?: return
        val scheme = uri.scheme ?: return

//        val okHttpDataSource = OkHttpDataSource.Factory(HttpClient.okHttpClient)
//        httpDataSource = okHttpDataSource

        val defaultHttpDataSource = DefaultHttpDataSource.Factory()
        defaultHttpDataSource.setKeepPostFor302Redirects(true)
        defaultHttpDataSource.setAllowCrossProtocolRedirects(true)
        tv.headers?.let {
            defaultHttpDataSource.setDefaultRequestProperties(it)
            it.forEach { (key, value) ->
                if (key.equals("user-agent", ignoreCase = true)) {
                    userAgent = value
                    return@forEach
                }
            }
        }

        httpDataSource = defaultHttpDataSource

        _mediaItem = MediaItem.fromUri(uri.toString())

        if (path.lowercase().endsWith(".m3u8")) {
            addSource(SourceType.HLS)
        } else if (path.lowercase().endsWith(".mpd")) {
            addSource(SourceType.DASH)
        } else if (scheme.lowercase() == "rtsp") {
            addSource(SourceType.RTSP)
        } else if (scheme.lowercase() == "rtmp") {
            // rtmp://ns8.indexforce.com/home/mystream
            addSource(SourceType.RTMP)
        } else {
//            addSource(SourceType.UNKNOWN)
//            addSource(SourceType.PROGRESSIVE)
            addSource(SourceType.HLS)
        }

        nextSource()
    }

    private fun addSource(sourceType: SourceType) {
        sources[0] = sourceType

        for (i in listOf(
            SourceType.PROGRESSIVE,
            SourceType.HLS,
            SourceType.RTSP,
            SourceType.RTMP,
            SourceType.DASH,
            SourceType.UNKNOWN
        )) {
            if (i != sourceType) {
                sources.add(i)
            }
        }
    }

    fun getSourceType(): SourceType {
        return tv.sourceType
    }

    fun getSourceTypeCurrent(): SourceType {
        return sources[sourceIndex]
    }

    fun nextSource() {
        sourceIndex = (sourceIndex + 1) % sources.size
    }

    @OptIn(UnstableApi::class)
    fun getSource(): MediaSource? {
        if (sources.isEmpty()) {
            return null
        }
        if (!::_mediaItem.isInitialized) {
            return null
        }
        sourceIndex = max(0, sourceIndex)
        sourceIndex = min(sourceIndex, sources.size - 1)

        return when (sources[sourceIndex]) {
            SourceType.HLS -> HlsMediaSource.Factory(httpDataSource).createMediaSource(_mediaItem)
            SourceType.RTSP -> if (userAgent.isEmpty()) {
                RtspMediaSource.Factory().createMediaSource(_mediaItem)
            } else {
                RtspMediaSource.Factory().setUserAgent(userAgent).createMediaSource(_mediaItem)
            }

            SourceType.RTMP -> {
                val rtmpDataSource = RtmpDataSource.Factory()
                ProgressiveMediaSource.Factory(rtmpDataSource)
                    .createMediaSource(_mediaItem)
            }

            SourceType.DASH -> DashMediaSource.Factory(httpDataSource).createMediaSource(_mediaItem)
            SourceType.PROGRESSIVE -> ProgressiveMediaSource.Factory(httpDataSource)
                .createMediaSource(_mediaItem)

            else -> null
        }
    }

    fun confirmSourceType() {
        // TODO save default sourceType
        tv.sourceType = sources[sourceIndex]
    }

    companion object {
        private const val TAG = "TVModel"
    }
}