package com.octopus.octopipe.engine.newpipe

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
object NewPipeExtractorEngine {
    private val initialized = AtomicBoolean(false)

    private fun initialize() {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(OctoPipeExtractorDownloader())
        }
    }

    fun search(query: String): List<VideoItem> {
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

    fun resolve(video: VideoItem): StreamInfo {
        initialize()
        val extractor = ServiceList.YouTube.getStreamExtractor(video.watchUrl)
        extractor.fetchPage()

        val videoStream = extractor.videoStreams.firstOrNull { !it.isVideoOnly }
            ?: extractor.videoStreams.firstOrNull()
            ?: throw IllegalStateException("NewPipe did not return a playable video stream.")

        val captions = extractor.subtitlesDefault.mapNotNull { subtitle ->
            val url = subtitle.content
            if (url.isBlank()) null else CaptionTrack(
                label = subtitle.name,
                languageCode = subtitle.languageTag.ifBlank { "und" },
                url = url
            )
        }

        val mime = videoStream.format?.mimeType ?: "video/mp4"
        return StreamInfo(video, videoStream.content, mime, captions)
    }
}
