package app.textapp.ui

import app.textapp.AppServices
import app.textapp.crypto.Crypto
import app.textapp.data.PubKeyRequest
import app.textapp.data.UserDto

/** Derives identity keys from the password and persists the session. */
suspend fun establishSession(token: String, user: UserDto, password: String, username: String) {
    val seed = Crypto.deriveSeed(password, username)
    val pub = Crypto.b64(Crypto.keyPairFromSeed(seed).second)
    // saveLogin first: it caches the token, which the authenticated key upload needs.
    AppServices.session.saveLogin(token, user, seed)
    // Always refresh the stored pubkey from the derived identity; a stale value
    // here would later overwrite the server key with the wrong one.
    AppServices.session.setMyPubKey(pub)
    if (user.pubKey != pub) {
        runCatching { AppServices.api.call { it.setPubKey(PubKeyRequest(pub)) } }
    }
    AppServices.startSession()
}
