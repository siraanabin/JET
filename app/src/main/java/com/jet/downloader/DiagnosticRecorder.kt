package com.jet.downloader
import android.content.Context
import android.net.Uri
import android.os.Build
import com.jet.downloader.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DiagnosticRecorder(context: Context, private val preferences: PreferencesManager, private val client: okhttp3.OkHttpClient) {
 companion object {
  private const val BASE_URL="https://jet-diagnostics.siraanabin.workers.dev"
  private const val MAX_EVENTS=200
  private const val MAX_TOP_LINKS=100
  private const val MAX_BATCH=15
  private val JSON_TYPE="application/json; charset=utf-8".toMediaType()
  private val EVENT_FIELDS=listOf("event_type","engine","fallbackReason","durationMs","format","resolution","sizeBytes","appVersion","androidVersion","deviceModel","abi")
 }
 private val queueFile=File(context.filesDir,"jet-diagnostic-queue.json")
 private val apiKey get()=BuildConfig.INGEST_API_KEY.trim()
 val isConfigured get()=apiKey.isNotEmpty()
 private fun tomorrowUtc()=LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString()
 private suspend fun blockedToday():Boolean {
  val until=runCatching{Instant.parse(preferences.getDiagnosticStopUntilUtc())}.getOrNull()?:return false
  return Instant.now().isBefore(until)
 }
 private fun readQueue()=runCatching{if(queueFile.isFile)JSONObject(queueFile.readText()) else JSONObject()}.getOrElse{JSONObject()}
 private fun writeQueue(q:JSONObject){
  val tmp=File(queueFile.parentFile,queueFile.name+".tmp");tmp.writeText(q.toString())
  if(!tmp.renameTo(queueFile)){queueFile.writeText(q.toString());tmp.delete()}
 }
 private fun parseStop(value:String):String?{
  if(value.isBlank())return null
  try{return Instant.parse(value).toString()}catch(_:Exception){}
  return try{LocalDate.parse(value).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString()}catch(_:Exception){null}
 }
 private suspend fun request(path:String,payload:JSONObject?,method:String="POST"):JSONObject{
  val key=apiKey;if(key.isBlank())throw IOException("Diagnostics are not configured")
  val id=preferences.getOrCreateDiagnosticInstallId();require(id.matches(Regex("[A-Za-z0-9._-]{16,128}")))
  val builder=Request.Builder().url(BASE_URL+path).header("Authorization","Bearer $key").header("X-Install-Id",id).header("Accept","application/json")
  if(method=="DELETE")builder.delete() else builder.post((payload?:JSONObject()).toString().toRequestBody(JSON_TYPE))
  repeat(2){attempt->
   try{
    client.newCall(builder.build()).execute().use{response->
     val body=response.body?.string().orEmpty()
     if(response.code==400||response.code==401||response.code==429){
      val server=runCatching{JSONObject(body).optString("stopUntilUtc")}.getOrDefault("")
      preferences.setDiagnosticStopUntilUtc(if(response.code==429)parseStop(server)?:tomorrowUtc() else tomorrowUtc())
      throw UploadBlockedException()
     }
     if(!response.isSuccessful)throw IOException("Diagnostic HTTP ${response.code}")
     return runCatching{JSONObject(body)}.getOrElse{JSONObject()}
    }
   }catch(blocked:UploadBlockedException){throw blocked}
   catch(error:IOException){if(attempt==1)throw error;delay(500L)}
  }
  throw IOException("Diagnostic request failed")
 }
 suspend fun settingsJson()=JSONObject().apply{
  put("diagnosticsEnabled",preferences.isDiagnosticsEnabled());put("topLinkCountingEnabled",preferences.isTopLinkCountingEnabled());put("apiKeyConfigured",isConfigured)
 }.toString()
 suspend fun setPreference(name:String,enabled:Boolean)=withContext(Dispatchers.IO){
  preferences.setDiagnosticPreference(name,enabled&&isConfigured)
  if(!enabled||!isConfigured){val q=readQueue();if(name=="diagnostics"){q.put("events",JSONArray());q.put("recent",JSONArray())}else if(name=="topLink")q.put("topLinks",JSONArray());writeQueue(q)}
 }
 suspend fun recentLogsJson()=withContext(Dispatchers.IO){
  if(!isConfigured||!preferences.isDiagnosticsEnabled())return@withContext "[]"
  val a=readQueue().optJSONArray("recent")?:JSONArray()
  JSONArray().also{out->for(i in (a.length()-20).coerceAtLeast(0) until a.length())out.put(a.getJSONObject(i))}.toString()
 }
 private suspend fun enqueue(event:JSONObject)=withContext(Dispatchers.IO){
  if(!isConfigured||!preferences.isDiagnosticsEnabled())return@withContext
  val q=readQueue();val events=q.optJSONArray("events")?:JSONArray()
  events.put(event);while(events.length()>MAX_EVENTS)events.remove(0);q.put("events",events)
  val recent=q.optJSONArray("recent")?:JSONArray();recent.put(event);while(recent.length()>20)recent.remove(0);q.put("recent",recent);writeQueue(q)
 }
 private fun newEvent(type:String)=JSONObject().apply{
  put("event_type",type);put("appVersion",BuildConfig.VERSION_NAME);put("androidVersion",Build.VERSION.RELEASE.orEmpty().take(32))
  put("deviceModel",(Build.MANUFACTURER+" "+Build.MODEL).take(80));put("abi",(Build.SUPPORTED_ABIS.firstOrNull()?:"unknown").take(32))
 }
 suspend fun recordFallback(reason:String){
  val safe=reason.substringBefore(':').replace(Regex("[^A-Za-z0-9_.-]"),"_").take(80)
  enqueue(newEvent("fallback").put("engine","yt-dlp").put("fallbackReason",safe))
 }
 suspend fun recordFailure(){enqueue(newEvent("failure").put("engine","unknown"))}
 suspend fun recordSuccess(url:String,format:String,resolution:String,size:Long,duration:Long){
  enqueue(newEvent("success").put("engine","download").put("format",format.take(20)).put("resolution",resolution.take(20)).put("sizeBytes",size.coerceAtLeast(0)).put("durationMs",duration.coerceAtLeast(0)))
  if(isConfigured&&preferences.isTopLinkCountingEnabled()){
   val uri=runCatching{Uri.parse(url)}.getOrNull();val host=uri?.host.orEmpty().lowercase()
   val valid=host=="youtu.be"||host=="youtube.com"||host.endsWith(".youtube.com")
   val id=if(!valid)null else uri?.getQueryParameter("v")
    ?:Regex("^/shorts/([A-Za-z0-9_-]{1,64})").find(uri?.path.orEmpty())?.groupValues?.get(1)
    ?:Regex("^/embed/([A-Za-z0-9_-]{1,64})").find(uri?.path.orEmpty())?.groupValues?.get(1)
    ?:if(host=="youtu.be")uri?.pathSegments?.firstOrNull() else null
   if(!id.isNullOrBlank()&&id.matches(Regex("[A-Za-z0-9_-]{1,64}")))withContext(Dispatchers.IO){
    val q=readQueue();val links=q.optJSONArray("topLinks")?:JSONArray();links.put(JSONObject().put("video_id",id))
    while(links.length()>MAX_TOP_LINKS)links.remove(0);q.put("topLinks",links);writeQueue(q)
   }
  }
 }
 suspend fun flushPending()=withContext(Dispatchers.IO){
  if(!isConfigured||blockedToday())return@withContext
  if(preferences.isDiagnosticsEnabled()){
   val q=readQueue();val events=q.optJSONArray("events")?:JSONArray()
   while(events.length()>0&&!blockedToday()){
    val batch=JSONArray()
    for(i in 0 until minOf(MAX_BATCH,events.length())){
     val source=events.getJSONObject(i);val clean=JSONObject()
     EVENT_FIELDS.forEach{f->if(source.has(f))clean.put(f,source.get(f))};batch.put(clean)
    }
    try{request("/v1/ingest",JSONObject().put("events",batch));repeat(batch.length()){events.remove(0)};q.put("events",events);writeQueue(q)}
    catch(_:UploadBlockedException){return@withContext}catch(_:IOException){return@withContext}
   }
  }
  if(preferences.isTopLinkCountingEnabled()&&!blockedToday()){
   val q=readQueue();val links=q.optJSONArray("topLinks")?:JSONArray()
   while(links.length()>0&&!blockedToday()){
    try{request("/v1/top-link",links.getJSONObject(0));links.remove(0);q.put("topLinks",links);writeQueue(q)}
    catch(_:UploadBlockedException){return@withContext}catch(_:IOException){return@withContext}
   }
  }
 }
 suspend fun submitReport(category:String,description:String,includeLogs:Boolean):Boolean=withContext(Dispatchers.IO){
  if(!isConfigured||blockedToday())return@withContext false
  require(category in setOf("bug","crash","performance","other"));require(description.length<=500)
  val logs=JSONArray()
  if(includeLogs&&preferences.isDiagnosticsEnabled()){
   val recent=readQueue().optJSONArray("recent")?:JSONArray()
   for(i in (recent.length()-20).coerceAtLeast(0) until recent.length()){
    val src=recent.getJSONObject(i);val clean=JSONObject();EVENT_FIELDS.forEach{f->if(src.has(f))clean.put(f,src.get(f))};logs.put(clean)
   }
  }
  try{request("/v1/bug-report",JSONObject().put("category",category).put("description",description).put("appVersion",BuildConfig.VERSION_NAME).put("logs",logs));true}catch(_:Exception){false}
 }
 suspend fun deleteMyData():Boolean=withContext(Dispatchers.IO){
  if(!isConfigured||blockedToday())return@withContext false
  try{request("/v1/me/data",null,"DELETE");queueFile.delete();preferences.resetDiagnosticInstallId();true}catch(_:Exception){false}
 }
 private class UploadBlockedException:IOException("Diagnostic upload paused for UTC day")
}
