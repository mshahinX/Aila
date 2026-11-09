package dev.personal.phoneagent.network

import dev.personal.phoneagent.agent.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class BackendClient(private val settings: SettingsStore) {
    private val client = OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS)
        .callTimeout(95,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    @Volatile private var active: Call? = null
    fun cancel() { active?.cancel() }
    private suspend fun request(path: String, method: String, data: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        SettingsStore.validateUrl(settings.baseUrl)
        val builder = Request.Builder().url(settings.baseUrl + path).header("Authorization","Bearer " + settings.token())
        if (method == "DELETE") builder.delete() else builder.post((data ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(builder.build()); active = call
            // Stop and cleanup timeouts abort transport too, including cancellation during setup.
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if(active === call) active=null
                    continuation.resumeWith(Result.failure(IOException("Backend connection failed (${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)})")))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result=runCatching {
                        response.use {
                            if(!it.isSuccessful) error("Backend HTTP ${it.code}; task paused. Check server configuration.")
                            val body=it.body ?: error("Empty backend response")
                            val source=body.source()
                            source.request(1_048_577)
                            require(source.buffer.size<=1_048_576) { "Backend response too large" }
                            JSONObject(source.readUtf8())
                        }
                    }
                    if(active === call) active=null
                    continuation.resumeWith(result)
                }
            })
        }
    }
    suspend fun create(command: String, mode: String): String {
        val id=request("/v1/sessions","POST",JSONObject().put("deviceId",settings.deviceId).put("command",command).put("mode",mode)).getString("sessionId")
        require(UUID.fromString(id).toString()==id) { "Invalid session ID" }
        return id
    }
    suspend fun next(session: String, sequence: Int, requestId: String, s: UiSnapshot, previous: String?, c: TaskContext): Proposal {
        require(!s.sensitive && !s.truncated) { "Sensitive or incomplete screen cannot be sent" }
        val result = request("/v1/sessions/$session/next","POST",JSONObject().put("requestId",requestId).put("sequence",sequence)
            .put("observation",s.json()).put("previousResult",previous ?: JSONObject.NULL).put("clarifications",JSONArray(c.clarifications)))
        require(result.getString("requestId") == requestId && result.getInt("sequence") == sequence) { "Mismatched response" }
        return Proposal.parse(result.getJSONObject("proposal"))
    }
    // ---------- v2 chatbot-first ----------
    /** [intent] = model's myGov classification (info/read/action/unclear); the phone re-checks it. */
    data class ChannelStep(val id: String, val app: String, val channel: String, val goal: String, val usesResultOf: String, val intent: String = "unclear")
    data class BotTurn(val type: String, val text: String, val summary: String)
    suspend fun plan(command: String, mode: String): List<ChannelStep> {
        val steps = request("/v1/plan","POST",JSONObject().put("command",command).put("mode",mode)).getJSONArray("steps")
        require(steps.length() in 1..8) { "Invalid plan" }
        return (0 until steps.length()).map { i ->
            val j = steps.getJSONObject(i)
            ChannelStep(j.getString("id"),j.getString("app"),j.getString("channel"),j.getString("goal").take(600),j.getString("usesResultOf"),j.optString("intent","unclear")).also {
                require(it.app in setOf("abb","mygov","whatsapp","azercell") && it.channel in setOf("bot","ui")) { "Invalid plan step" }
            }
        }
    }
    suspend fun botTurn(mode: String, app: String, goal: String, transcript: List<Pair<String,String>>, prior: List<Pair<String,String>>, clarifications: List<String>): BotTurn {
        val body = JSONObject().put("mode",mode).put("app",app).put("goal",goal.take(600))
            .put("transcript",JSONArray(transcript.takeLast(24).map { JSONObject().put("role",it.first).put("text",Privacy.mask(it.second).take(2000)) }))
            .put("priorResults",JSONArray(prior.takeLast(8).map { JSONObject().put("stepId",it.first).put("summary",Privacy.mask(it.second).take(1500)) }))
            .put("clarifications",JSONArray(clarifications.takeLast(8).map { it.take(1000) }))
        val t = request("/v1/bot-turn","POST",body).getJSONObject("turn")
        return BotTurn(t.getString("type"),t.getString("text").take(1000),t.getString("summary").take(1500)).also {
            require(it.type in setOf("say","done","ask_user","fallback_ui")) { "Invalid bot turn" }
        }
    }
    suspend fun close(session: String) { request("/v1/sessions/$session","DELETE") }
}
