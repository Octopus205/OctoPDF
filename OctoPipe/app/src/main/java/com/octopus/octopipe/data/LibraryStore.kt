package com.octopus.octopipe.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LibraryStore(context:Context) {
    private val prefs=context.getSharedPreferences("octopipe_library",Context.MODE_PRIVATE)
    fun favorites()=readVideos("favorites")
    fun history()=readVideos("history")
    fun subscriptions()=readChannels()
    fun toggle(v:VideoItem):Boolean {
        val list=favorites().toMutableList()
        val exists=list.any{it.id==v.id&&it.service==v.service}
        if(exists)list.removeAll{it.id==v.id&&it.service==v.service}else list.add(0,v)
        writeVideos("favorites",list.take(100));return !exists
    }
    fun addHistory(v:VideoItem) {
        val list=history().filterNot{it.id==v.id&&it.service==v.service}.toMutableList()
        list.add(0,v);writeVideos("history",list.take(50))
    }
    fun toggleSubscription(channel:ChannelItem):Boolean {
        val list=subscriptions().toMutableList()
        val exists=list.any{it.id==channel.id&&it.service==channel.service}
        if(exists)list.removeAll{it.id==channel.id&&it.service==channel.service}else list.add(0,channel)
        val array=JSONArray();list.take(200).forEach{array.put(JSONObject().apply{
            put("id",it.id);put("name",it.name);put("thumbnail",it.thumbnail);put("service",it.service);put("url",it.url);put("subscribers",it.subscribers)
        })}
        prefs.edit().putString("subscriptions",array.toString()).apply()
        return !exists
    }
    private fun readChannels():List<ChannelItem>{
        val a=JSONArray(prefs.getString("subscriptions","[]")?:"[]")
        return (0 until a.length()).mapNotNull{i->val o=a.optJSONObject(i)?:return@mapNotNull null
            ChannelItem(o.optString("id"),o.optString("name"),o.optString("thumbnail"),o.optString("service"),o.optString("url"),o.optLong("subscribers"))}
    }
    private fun readVideos(key:String):List<VideoItem>{
        val a=JSONArray(prefs.getString(key,"[]")?:"[]")
        return (0 until a.length()).mapNotNull{i->val o=a.optJSONObject(i)?:return@mapNotNull null
            VideoItem(o.optString("id"),o.optString("title"),o.optString("author"),o.optString("thumbnail"),o.optLong("duration"),o.optLong("views"),o.optString("service"),o.optString("watchUrl"))}
    }
    private fun writeVideos(key:String,list:List<VideoItem>){
        val a=JSONArray();list.forEach{v->a.put(JSONObject().apply{
            put("id",v.id);put("title",v.title);put("author",v.author);put("thumbnail",v.thumbnail);put("duration",v.durationSeconds);put("views",v.views);put("service",v.service);put("watchUrl",v.watchUrl)
        })}
        prefs.edit().putString(key,a.toString()).apply()
    }
}
