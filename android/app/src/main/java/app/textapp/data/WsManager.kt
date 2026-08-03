package app.textapp.data

import app.textapp.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.min

sealed interface WsEvent {
    data class Message(val m: MessageDto) : WsEvent
    data class Typing(val conv: String, val username: String) : WsEvent
    data class Presence(val username: String, val online: Boolean) : WsEvent
    data class Read(val conv: String, val by: String) : WsEvent
    data class FriendRequest(val from: UserDto) : WsEvent
    data class FriendAccept(val from: UserDto) : WsEvent
}

class WsManager(
    private val session: SessionManager,
    private val scope: CoroutineScope,
) {
    private var ws: WebSocket? = null
    private var closedByUser = false
    private var attempts = 0
    private val _events = Channel<WsEvent>(Channel.BUFFERED)
    val events: Flow<WsEvent> = _events.receiveAsFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    fun start() {
        closedByUser = false
        if (ws == null) connect()
    }

    fun stop() {
        closedByUser = true
        ws?.close(1000, "bye")
        ws = null
        _connected.value = false
    }

    /** Reconnect to the currently selected server (e.g. after changing it in Settings). */
    fun restart() {
        stop()
        start()
    }

    private fun connect() {
        scope.launch {
            val token = session.token() ?: return@launch
            val base = BuildConfig.SERVER_URL.trimEnd('/')
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://")
            val url = "$base/ws?token=${URLEncoder.encode(token, "UTF-8")}"
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url).build()
            ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempts = 0
                    _connected.value = true
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    parseEvent(text)?.let { _events.trySend(it) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _connected.value = false
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _connected.value = false
                    scheduleReconnect()
                }
            })
        }
    }

    private fun scheduleReconnect() {
        if (closedByUser) return
        scope.launch {
            val delayMs = min(30_000L, 1_000L shl min(attempts, 5))
            attempts++
            delay(delayMs)
            connect()
        }
    }

    fun sendTyping(convId: String) {
        ws?.send("""{"t":"typing","conv":"$convId"}""")
    }

    fun sendRead(convId: String) {
        ws?.send("""{"t":"read","conv":"$convId"}""")
    }

    private fun parseEvent(text: String): WsEvent? {
        return try {
            val el = AppJson.parseToJsonElement(text).jsonObject
            when (el["t"]?.jsonPrimitive?.content) {
                "msg" -> WsEvent.Message(AppJson.decodeFromJsonElement(el.getValue("m")))
                "typing" -> WsEvent.Typing(
                    el.getValue("conv").jsonPrimitive.content,
                    el.getValue("username").jsonPrimitive.content,
                )
                "presence" -> WsEvent.Presence(
                    el.getValue("username").jsonPrimitive.content,
                    el.getValue("online").jsonPrimitive.content == "true",
                )
                "read" -> WsEvent.Read(
                    el.getValue("conv").jsonPrimitive.content,
                    el.getValue("by").jsonPrimitive.content,
                )
                "friend_request" -> WsEvent.FriendRequest(AppJson.decodeFromJsonElement(el.getValue("from")))
                "friend_accept" -> WsEvent.FriendAccept(AppJson.decodeFromJsonElement(el.getValue("from")))
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
