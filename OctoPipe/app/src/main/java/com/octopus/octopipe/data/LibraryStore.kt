package com.octopus.octopipe.data
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
class LibraryStore(context:Context) {
 private val prefs=context.getSharedPreferences("octopipe_library",Context.MODE_PRIVATE)
 fun favorites()=read("favorites")
 fun history()=read("history")
 fun toggle(v:VideoItem):Boolean { val l=favorites().toMutableList(); val exists=l.any{it.id==v.id&&it.service==v.service}; if(exists)l.removeAll{it.id==v.id&&it.service==v.service}else l.add(0,v); write("favorites",l.take(100)); return !exists }
 fun addHistory(v:VideoItem) { val l=history().filterNot{it.id==v.id&&it.service==v.service}.toMutableList(); l.add(0,v); write("history",l.take(50)) }
 private fun read(k:String):List<VideoItem> { val a=JSONArray(prefs.getString(k,"[]")?:"[]"); return (0 until a.length()).mapNotNull{i->val o=a.optJSONObject(i)?:return@mapNotNull null; VideoItem(o.optString("id"),o.optString("title"),o.optString("author"),o.optString("thumbnail"),o.optLong("duration"),o.optLong("views"),o.optString("service"),o.optString("watchUrl"))} }
 private fun write(k:String,l:List<VideoItem>) { val a=JSONArray(); l.forEach{v->a.put(JSONObject().apply{put("id",v.id);put("title",v.title);put("author",v.author);put("thumbnail",v.thumbnail);put("duration",v.durationSeconds);put("views",v.views);put("service",v.service);put("watchUrl",v.watchUrl)})}; prefs.edit().putString(k,a.toString()).apply() }
}
