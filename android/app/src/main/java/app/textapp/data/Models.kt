package app.textapp.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val pubKey: String? = null,
)

@Serializable
data class AuthResponse(val token: String, val user: UserDto)

@Serializable
data class UserResponse(val user: UserDto)

@Serializable
data class MessageDto(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderUsername: String? = null,
    val type: String,
    val payload: String,
    val mediaId: String? = null,
    val readAt: Long? = null,
    val createdAt: Long = 0,
)

@Serializable
data class ConversationDto(
    val id: String,
    val peer: UserDto,
    val lastMsg: MessageDto? = null,
    val unread: Int = 0,
    val createdAt: Long = 0,
)

@Serializable
data class ConversationResponse(val conversation: ConversationDto)

@Serializable
data class ConversationsResponse(val conversations: List<ConversationDto>)

@Serializable
data class MessagesResponse(val conversation: ConversationDto, val messages: List<MessageDto>)

@Serializable
data class MessageResponse(val message: MessageDto)

@Serializable
data class SearchResponse(val users: List<UserDto>)

@Serializable
data class FriendItem(val user: UserDto, val since: Long = 0, val online: Boolean = false)

@Serializable
data class FriendRequestItem(val user: UserDto, val createdAt: Long = 0)

@Serializable
data class FriendsResponse(val friends: List<FriendItem> = emptyList(), val requests: List<FriendRequestItem> = emptyList())

@Serializable
data class UploadResponse(val mediaId: String, val size: Long)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class ErrorBody(val error: String, val message: String? = null)

// ---------- request bodies ----------

@Serializable
data class RegisterRequest(val username: String, val email: String, val password: String)

@Serializable
data class VerifyRequest(val username: String, val code: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class UsernameRequest(val username: String)

@Serializable
data class RespondRequest(val username: String, val accept: Boolean)

@Serializable
data class PubKeyRequest(val pubKey: String)

@Serializable
data class SendMessageRequest(
    val type: String,
    val payload: String,
    @SerialName("mediaId") val mediaId: String? = null,
)

@Serializable
data class PushRegisterRequest(val token: String)

// ---------- decrypted content ----------

@Serializable
data class ThumbDto(val id: String, val key: String, val mime: String, val w: Int, val h: Int)

@Serializable
data class MediaPayloadDto(
    val mediaId: String,
    val mediaKey: String,
    val mime: String,
    val name: String,
    val size: Long,
    val w: Int = 0,
    val h: Int = 0,
    val thumb: ThumbDto? = null,
)

@Serializable
data class PayloadDto(val t: String, val text: String? = null, val media: MediaPayloadDto? = null)
