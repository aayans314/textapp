package app.textapp.data

import android.content.Context
import app.textapp.crypto.Crypto
import app.textapp.crypto.MediaCipher
import app.textapp.media.MediaPreparer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.File

data class ResolvedMedia(val file: File, val mime: String, val w: Int, val h: Int)

data class DecryptResult(val payload: PayloadDto?, val reason: String?)

class ChatRepository(
    private val context: Context,
    private val api: ApiClient,
    private val session: SessionManager,
    private val ws: WsManager,
    private val mediaPreparer: MediaPreparer,
    private val scope: CoroutineScope,
) {
    private val _conversations = MutableStateFlow<List<ConversationDto>>(emptyList())
    val conversations: StateFlow<List<ConversationDto>> = _conversations

    private val _messages = MutableStateFlow<Map<String, List<MessageDto>>>(emptyMap())
    val messages: StateFlow<Map<String, List<MessageDto>>> = _messages

    private val _friends = MutableStateFlow<List<FriendItem>>(emptyList())
    val friends: StateFlow<List<FriendItem>> = _friends

    private val _requests = MutableStateFlow<List<FriendRequestItem>>(emptyList())
    val requests: StateFlow<List<FriendRequestItem>> = _requests

    private val _presence = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val presence: StateFlow<Map<String, Boolean>> = _presence

    private val _typing = MutableStateFlow<Map<String, String>>(emptyMap())
    val typing: StateFlow<Map<String, String>> = _typing

    private val _activeConversation = MutableStateFlow<String?>(null)
    val activeConversation: StateFlow<String?> = _activeConversation

    fun start() {
        scope.launch {
            ws.events.collect { event ->
                when (event) {
                    is WsEvent.Message -> onMessage(event.m)
                    is WsEvent.Presence -> _presence.value = _presence.value + (event.username to event.online)
                    is WsEvent.Typing -> onTyping(event.conv, event.username)
                    is WsEvent.Read -> onRead(event.conv, event.by)
                    is WsEvent.FriendRequest -> refreshFriends()
                    is WsEvent.FriendAccept -> refreshFriends()
                }
            }
        }
        scope.launch {
            refreshConversations()
            refreshFriends()
        }
    }

    fun setActiveConversation(convId: String?) {
        _activeConversation.value = convId
    }

    suspend fun refreshConversations() {
        runCatching {
            val resp = api.call { it.conversations() }
            resp.conversations.forEach { conv ->
                session.setConvPeer(conv.id, conv.peer.username)
                conv.peer.pubKey?.let { session.setPeerPubKey(conv.peer.username, it) }
            }
            _conversations.value = resp.conversations
        }
    }

    suspend fun refreshFriends() {
        runCatching {
            val resp = api.call { it.friends() }
            _friends.value = resp.friends
            _requests.value = resp.requests
            resp.friends.forEach { f ->
                f.user.pubKey?.let { session.setPeerPubKey(f.user.username, it) }
            }
            _presence.value = resp.friends.associate { it.user.username to it.online }
        }
    }

    suspend fun searchUsers(q: String): List<UserDto> {
        if (q.isBlank()) return emptyList()
        return api.call { it.search(q.trim()) }.users
    }

    suspend fun requestFriend(username: String) {
        api.call { it.requestFriend(UsernameRequest(username)) }
        refreshFriends()
    }

    suspend fun respondFriend(username: String, accept: Boolean) {
        api.call { it.respondFriend(RespondRequest(username, accept)) }
        refreshFriends()
    }

    suspend fun openConversation(username: String): ConversationDto {
        val resp = api.call { it.openConversation(UsernameRequest(username)) }
        session.setConvPeer(resp.conversation.id, resp.conversation.peer.username)
        resp.conversation.peer.pubKey?.let { session.setPeerPubKey(resp.conversation.peer.username, it) }
        refreshConversations()
        return resp.conversation
    }

    suspend fun loadMessages(convId: String, limit: Int = 50) {
        val resp = api.call { it.messages(convId, limit) }
        session.setConvPeer(resp.conversation.id, resp.conversation.peer.username)
        resp.conversation.peer.pubKey?.let { session.setPeerPubKey(resp.conversation.peer.username, it) }
        _messages.value = _messages.value + (convId to resp.messages)
    }

    suspend fun markRead(convId: String) {
        runCatching { api.call { it.markRead(convId) } }
        ws.sendRead(convId)
        val convs = _conversations.value.map { c ->
            if (c.id == convId) c.copy(unread = 0) else c
        }
        _conversations.value = convs
    }

    suspend fun sendText(conv: ConversationDto, text: String): MessageDto {
        val priv = privateKey() ?: throw ApiException("not signed in")
        val peerPub = Crypto.decodeB64(conv.peer.pubKey ?: throw ApiException("peer has no encryption key"))
        val key = Crypto.conversationKey(priv, peerPub, conv.id)
        val payload = Crypto.encrypt(key, AppJson.encodeToString(PayloadDto(t = "text", text = text)).encodeToByteArray())
        val resp = api.call { it.sendMessage(conv.id, SendMessageRequest("text", payload)) }
        insertMessage(resp.message)
        return resp.message
    }

    suspend fun sendMedia(
        conv: ConversationDto,
        uri: android.net.Uri,
        isVideo: Boolean,
        onStage: (String) -> Unit,
        onProgress: (Float) -> Unit,
    ): MessageDto {
        val priv = privateKey() ?: throw ApiException("not signed in")
        val peerPub = Crypto.decodeB64(conv.peer.pubKey ?: throw ApiException("peer has no encryption key"))
        val convKey = Crypto.conversationKey(priv, peerPub, conv.id)

        onStage("Compressing…")
        val prepared = mediaPreparer.prepare(uri, isVideo)
        val sizeMb = prepared.file.length() / 1048576f
        if (prepared.file.length() > 25 * 1024 * 1024) {
            throw ApiException("compressed file is still ${"%.1f".format(sizeMb)} MB - choose a shorter video")
        }

        onStage("Encrypting…")
        val mediaKey = Crypto.randomKey()
        val cipherFile = File(context.cacheDir, "enc_${System.currentTimeMillis()}.bin")
        MediaCipher.encryptFile(prepared.file, cipherFile, mediaKey)

        var thumb: ThumbDto? = null
        try {
            onStage("Preparing thumbnail…")
            val thumbPrep = mediaPreparer.prepareThumb(prepared.file, isVideo)
            val thumbKey = Crypto.randomKey()
            val thumbCipher = File(context.cacheDir, "enc_t_${System.currentTimeMillis()}.bin")
            MediaCipher.encryptFile(thumbPrep.file, thumbCipher, thumbKey)
            onStage("Uploading…")
            val thumbResp = api.uploadMedia(conv.id, thumbCipher) { onProgress(it * 0.15f) }
            thumb = ThumbDto(thumbResp.mediaId, Crypto.b64(thumbKey), "image/jpeg", thumbPrep.w, thumbPrep.h)
        } catch (e: Exception) {
            // thumbnails are best-effort
        }

        onStage("Uploading…")
        val mediaResp = api.uploadMedia(conv.id, cipherFile) { onProgress(0.15f + it * 0.85f) }
        val payload = Crypto.encrypt(
            convKey,
            AppJson.encodeToString(
                PayloadDto(
                    t = "media",
                    media = MediaPayloadDto(
                        mediaId = mediaResp.mediaId,
                        mediaKey = Crypto.b64(mediaKey),
                        mime = prepared.mime,
                        name = prepared.name,
                        size = prepared.file.length(),
                        w = prepared.w,
                        h = prepared.h,
                        thumb = thumb,
                    ),
                ),
            ).encodeToByteArray(),
        )
        val resp = api.call { it.sendMessage(conv.id, SendMessageRequest("media", payload, mediaResp.mediaId)) }
        cipherFile.delete()
        insertMessage(resp.message)
        return resp.message
    }

    /** Decrypt a message's payload, reporting why decryption failed (for diagnostics). */
    suspend fun decryptMessageReason(m: MessageDto): DecryptResult {
        if (m.type != "text" && m.type != "media") return DecryptResult(null, "type ${m.type}")
        val priv = privateKey()
        if (priv == null) return DecryptResult(null, "no session key")
        var peerName = session.convPeer(m.conversationId)
        var peerPub = peerName?.let { session.peerPubKey(it) }
        if (peerName == null || peerPub == null) {
            // WS-delivered messages can arrive before the conversation mapping is
            // cached; fetch it now instead of giving up.
            runCatching { refreshConversations() }
            runCatching { refreshFriends() }
            peerName = session.convPeer(m.conversationId)
            peerPub = peerName?.let { session.peerPubKey(it) }
        }
        if (peerName == null) return DecryptResult(null, "no conversation mapping")
        if (peerPub == null) return DecryptResult(null, "no peer key for $peerName")
        return try {
            val key = Crypto.conversationKey(priv, Crypto.decodeB64(peerPub), m.conversationId)
            val plain = Crypto.decrypt(key, m.payload)
            DecryptResult(AppJson.decodeFromString<PayloadDto>(plain.decodeToString()), null)
        } catch (e: Exception) {
            DecryptResult(null, "crypto ${e::class.simpleName}: ${e.message?.take(80)}")
        }
    }

    /** Decrypt a message's payload. Returns null when keys are unavailable. */
    suspend fun decryptMessage(m: MessageDto): PayloadDto? = decryptMessageReason(m).payload

    /** Downloads + decrypts a media blob (full or thumb) into cache. */
    suspend fun resolveMedia(m: MessageDto, content: PayloadDto, thumb: Boolean): ResolvedMedia? {
        val media = content.media ?: return null
        val targetId: String
        val targetKey: String
        val targetMime: String
        val targetW: Int
        val targetH: Int
        if (thumb) {
            val t = media.thumb ?: return null
            targetId = t.id
            targetKey = t.key
            targetMime = t.mime
            targetW = t.w
            targetH = t.h
        } else {
            targetId = media.mediaId
            targetKey = media.mediaKey
            targetMime = media.mime
            targetW = media.w
            targetH = media.h
        }
        val dir = File(context.cacheDir, "media").apply { mkdirs() }
        val cache = File(dir, "$targetId.bin")
        if (cache.exists() && cache.length() > 0) {
            return ResolvedMedia(cache, targetMime, targetW, targetH)
        }
        val bytes = api.downloadMedia(targetId)
        val tmp = File(dir, "$targetId.tmp")
        tmp.writeBytes(bytes)
        MediaCipher.decryptFile(tmp, cache, Crypto.decodeB64(targetKey))
        tmp.delete()
        return ResolvedMedia(cache, targetMime, targetW, targetH)
    }

    fun sendTyping(convId: String) {
        ws.sendTyping(convId)
    }

    private suspend fun privateKey(): ByteArray? {
        val seed = session.seed() ?: return null
        return Crypto.privateKeyFromSeed(seed)
    }

    private suspend fun onMessage(m: MessageDto) {
        insertMessage(m)
        val active = _activeConversation.value
        if (m.senderId != session.currentState().userId && active != m.conversationId) {
            val convs = _conversations.value.map { c ->
                if (c.id == m.conversationId) c.copy(lastMsg = m, unread = c.unread + 1) else c
            }
            _conversations.value = convs
        } else if (active == m.conversationId) {
            markRead(m.conversationId)
        }
    }

    private fun onTyping(convId: String, username: String) {
        _typing.value = _typing.value + (convId to username)
        scope.launch {
            kotlinx.coroutines.delay(2500)
            _typing.value = _typing.value - convId
        }
    }

    private fun onRead(convId: String, by: String) {
        val updated = _messages.value[convId]?.map { m ->
            if (m.senderUsername != by && m.readAt == null) m.copy(readAt = System.currentTimeMillis()) else m
        }
        if (updated != null) _messages.value = _messages.value + (convId to updated)
        _conversations.value = _conversations.value.map { c ->
            if (c.id == convId && c.lastMsg?.senderUsername == by) c.copy(unread = 0) else c
        }
    }

    private suspend fun insertMessage(m: MessageDto) {
        val existing = _messages.value[m.conversationId] ?: emptyList()
        val merged = (existing + m).distinctBy { it.id }.sortedBy { it.createdAt }
        _messages.value = _messages.value + (m.conversationId to merged)
        val convs = _conversations.value.map { c ->
            if (c.id == m.conversationId) c.copy(lastMsg = m) else c
        }
        if (convs.none { it.id == m.conversationId }) {
            refreshConversations()
        } else {
            _conversations.value = convs
        }
    }
}
