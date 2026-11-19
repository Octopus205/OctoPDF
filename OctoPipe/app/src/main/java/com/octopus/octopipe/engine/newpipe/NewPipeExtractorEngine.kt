package com.octopus.octopipe.engine.newpipe

import com.octopus.octopipe.engine.VideoEngine
import com.octopus.octopipe.data.CaptionTrack
import com.octopus.octopipe.data.ServiceProvider
import com.octopus.octopipe.data.StreamInfo
import com.octopus.octopipe.data.VideoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First-party OctoPipe adapter around NewPipe Extractor.
 * Only normalized OctoPipe data types cross this boundary.
 */
object NewPipeExtractorEngine : VideoEngine {
    override val id: String = "newpipe-extractor"
    override fun supports(service: String): Boolean = service == ServiceProvider.YOUTUBE.name
    private val initialized = AtomicBoolean(false)

    private fun initialize() {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(OctoPipeExtractorDownloader())
        }
    }

    override fun search(query: String): List<VideoItem> {
        initialize()
        val service = ServiceList.YouTube
        val extractor = service.getSearchExtractor(query)
        extractor.fetchPage()
        return extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .map { item ->
                VideoItem(
                    id = item.url,
                    title = item.name,
                    author = item.uploaderName,
                    thumbnail = item.thumbnails.firstOrNull()?.url ?: "",
                    durationSeconds = item.duration,
                    views = item.viewCount,
                    service = ServiceProvider.YOUTUBE.name,
                    watchUrl = item.url
                )
            }
    }

    override fun resolve(video: VideoItem): StreamInfo {
        initialize()
        val extractor = ServiceList.YouTube.getStreamExtractor(video.watchUrl)
        extractor.fetchPage()

        val videoStream = extractor.videoStreams.firstOrNull { !it.isVideoOnly }
            ?: throw IllegalStateException("This video only exposes a separate video track. A compatible combined stream was not returned.")
        if (!videoStream.isUrl) {
            throw IllegalStateException("This video exposes a manifest instead of a direct stream. Adaptive playback support is required.")
        }

        val captions = extractor.subtitlesDefault.mapNotNull { subtitle ->
            val url = subtitle.content
            if (url.isBlank()) null else CaptionTrack(
                label = subtitle.name ?: subtitle.languageTag,
                languageCode = subtitle.languageTag.ifBlank { "und" },
                url = url
            )
        }

        val mime = videoStream.format?.mimeType ?: "video/mp4"
        return StreamInfo(video, videoStream.content, mime, captions)
    }
}
