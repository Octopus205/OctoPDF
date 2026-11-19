package com.octopus.octopipe.engine

import com.octopus.octopipe.data.StreamInfo
import com.octopus.octopipe.data.VideoItem

/**
 * Provider-independent boundary. Implementations translate their own network
 * and extraction models into OctoPipe-owned data classes.
 */
interface VideoEngine {
    val id: String
    fun supports(service: String): Boolean
    fun search(query: String): List<VideoItem>
    fun resolve(video: VideoItem): StreamInfo
}
