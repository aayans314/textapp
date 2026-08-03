package app.textapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.textapp.crypto.Crypto
import app.textapp.crypto.KeyStoreCrypto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

data class SessionState(
    val token: String? = null,
    val userId: String? = null,
    val username: String? = null,
    val pubKey: String? = null,
)

class SessionManager(private val context: Context) {
    @Volatile
    private var cachedToken: String? = null

    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val USER_ID = stringPreferencesKey("user_id")
        val USERNAME = stringPreferencesKey("username")
        val MY_PUB_KEY = stringPreferencesKey("my_pub_key")
        val WRAPPED_SEED = stringPreferencesKey("wrapped_seed")
        val FCM_TOKEN = stringPreferencesKey("fcm_token")
        val PEER_KEYS = stringPreferencesKey("peer_keys_json")   // username -> pubKey
        val CONV_PEERS = stringPreferencesKey("conv_peers_json") // convId -> username
    }

    val state: Flow<SessionState> = context.dataStore.data.map { p ->
        SessionState(
            token = p[Keys.TOKEN],
            userId = p[Keys.USER_ID],
            username = p[Keys.USERNAME],
            pubKey = p[Keys.MY_PUB_KEY],
        )
    }

    suspend fun currentState(): SessionState = state.first()
    suspend fun token(): String? = currentState().token
    suspend fun username(): String? = currentState().username
    suspend fun myPubKey(): String? = currentState().pubKey

    suspend fun saveLogin(token: String, user: UserDto, seed: ByteArray) {
        cachedToken = token
        val wrapped = KeyStoreCrypto.wrap(seed)
        context.dataStore.edit { p ->
            p[Keys.TOKEN] = token
            p[Keys.USER_ID] = user.id
            p[Keys.USERNAME] = user.username
            p[Keys.MY_PUB_KEY] = user.pubKey.orEmpty()
            p[Keys.WRAPPED_SEED] = android.util.Base64.encodeToString(wrapped, android.util.Base64.NO_WRAP)
        }
    }

    suspend fun setMyPubKey(pubKey: String) {
        context.dataStore.edit { it[Keys.MY_PUB_KEY] = pubKey }
    }

    /** Unwraps the password-derived seed. Null when not logged in or key invalidated. */
    suspend fun seed(): ByteArray? {
        val wrappedB64 = context.dataStore.data.first()[Keys.WRAPPED_SEED] ?: return null
        return try {
            KeyStoreCrypto.unwrap(android.util.Base64.decode(wrappedB64, android.util.Base64.NO_WRAP))
        } catch (e: Exception) {
            null
        }
    }

    suspend fun setFcmToken(token: String) {
        context.dataStore.edit { it[Keys.FCM_TOKEN] = token }
    }

    suspend fun fcmToken(): String? = context.dataStore.data.first()[Keys.FCM_TOKEN]

    suspend fun setPeerPubKey(username: String, pubKey: String) {
        context.dataStore.edit { p ->
            val map = decodeMap(p[Keys.PEER_KEYS]).toMutableMap()
            map[username] = pubKey
            p[Keys.PEER_KEYS] = encodeMap(map)
        }
    }

    suspend fun peerPubKey(username: String): String? =
        decodeMap(context.dataStore.data.first()[Keys.PEER_KEYS])[username]

    suspend fun setConvPeer(convId: String, username: String) {
        context.dataStore.edit { p ->
            val map = decodeMap(p[Keys.CONV_PEERS]).toMutableMap()
            map[convId] = username
            p[Keys.CONV_PEERS] = encodeMap(map)
        }
    }

    suspend fun convPeer(convId: String): String? =
        decodeMap(context.dataStore.data.first()[Keys.CONV_PEERS])[convId]

    suspend fun logout() {
        cachedToken = null
        context.dataStore.edit { it.clear() }
        KeyStoreCrypto.deleteKey()
    }

    fun cachedToken(): String? = cachedToken

    suspend fun warmCache() {
        cachedToken = token()
    }

    private fun decodeMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(raw)
        }.getOrDefault(emptyMap())
    }

    private fun encodeMap(map: Map<String, String>): String =
        kotlinx.serialization.json.Json.encodeToString(map)
}
