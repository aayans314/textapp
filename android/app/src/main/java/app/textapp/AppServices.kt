package app.textapp

import android.content.Context
import app.textapp.data.ApiClient
import app.textapp.data.ChatRepository
import app.textapp.data.ChatTarget
import app.textapp.data.PubKeyRequest
import app.textapp.data.SessionManager
import app.textapp.data.WsManager
import app.textapp.crypto.Crypto
import app.textapp.media.MediaPreparer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object AppServices {
    lateinit var session: SessionManager
    lateinit var api: ApiClient
    lateinit var ws: WsManager
    lateinit var repository: ChatRepository
    lateinit var mediaPreparer: MediaPreparer
    val pendingChat = MutableStateFlow<ChatTarget?>(null)

    /** Kept only in memory between register/verify screens so the user never re-enters it. */
    @Volatile
    var pendingPassword: String? = null

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        session = SessionManager(context.applicationContext)
        api = ApiClient(session)
        ws = WsManager(session, scope)
        mediaPreparer = MediaPreparer(context.applicationContext)
        repository = ChatRepository(context.applicationContext, api, session, ws, mediaPreparer, scope)
        scope.launch {
            session.warmCache()
            if (session.token() != null) {
                ws.start()
                repository.start()
            }
        }
    }

    fun startSession() {
        ws.start()
        repository.start()
        scope.launch {
            runCatching {
                // Ensure the token is cached before any authenticated call; at cold
                // start this can race with AppServices.init's warmCache().
                session.warmCache()
                // Upload the key derived from the CURRENT seed, never a stored
                // value that can go stale - a stale key silently breaks decryption
                // for everyone messaging this account.
                val seed = session.seed() ?: return@runCatching
                val pub = Crypto.b64(Crypto.keyPairFromSeed(seed).second)
                val me = api.call { it.me() }
                if (me.user.pubKey != pub) {
                    session.setMyPubKey(pub)
                    api.call { it.setPubKey(PubKeyRequest(pub)) }
                }
            }
        }
    }
}
