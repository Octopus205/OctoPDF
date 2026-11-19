package com.octopus.octopipe.data
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
object SponsorBlockRepository {
 private val client=OkHttpClient()
 suspend fun segments(id:String):List<SponsorSegment> = withContext(Dispatchers.IO) {
  try {
   val b=Uri.parse("https://sponsor.ajay.app/api/skipSegments").buildUpon().appendQueryParameter("videoID",id)
   listOf("sponsor","intro","outro","selfpromo","interaction","music_offtopic").forEach { b.appendQueryParameter("category",it) }
   val req=Request.Builder().url(b.build().toString()).header("User-Agent","OctoPipe/0.2.0").build()
   client.newCall(req).execute().use { r ->
    if(!r.isSuccessful)return@withContext emptyList()
    val a=JSONArray(r.body?.string().orEmpty())
    (0 until a.length()).mapNotNull { i -> val o=a.optJSONObject(i) ?: return@mapNotNull null
     val range=o.optJSONArray("segment") ?: return@mapNotNull null
     if(range.length()<2)null else SponsorSegment(range.optDouble(0),range.optDouble(1),o.optString("category"))
    }.sortedBy { it.startSeconds }
   }
  } catch(_:Exception) { emptyList() }
 }
}
