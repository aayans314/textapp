package app.textapp.data

import app.textapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

class ApiException(message: String, val serverError: String? = null) : Exception(message)

interface TextApi {
    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): OkResponse

    @POST("api/auth/verify")
    suspend fun verify(@Body body: VerifyRequest): AuthResponse

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("api/auth/resend")
    suspend fun resend(@Body body: UsernameRequest): OkResponse

    @GET("api/users/me")
    suspend fun me(): UserResponse

    @POST("api/users/pubkey")
    suspend fun setPubKey(@Body body: PubKeyRequest): OkResponse

    @GET("api/users/search")
    suspend fun search(@Query("q") q: String): SearchResponse

    @GET("api/friends")
    suspend fun friends(): FriendsResponse

    @POST("api/friends/request")
    suspend fun requestFriend(@Body body: UsernameRequest): OkResponse

    @POST("api/friends/respond")
    suspend fun respondFriend(@Body body: RespondRequest): OkResponse

    @POST("api/conversations")
    suspend fun openConversation(@Body body: UsernameRequest): ConversationResponse

    @GET("api/conversations")
    suspend fun conversations(): ConversationsResponse

    @GET("api/conversations/{id}/messages")
    suspend fun messages(@Path("id") id: String, @Query("limit") limit: Int = 50): MessagesResponse

    @POST("api/conversations/{id}/messages")
    suspend fun sendMessage(@Path("id") id: String, @Body body: SendMessageRequest): MessageResponse

    @POST("api/conversations/{id}/read")
    suspend fun markRead(@Path("id") id: String, @Body body: OkResponse = OkResponse()): OkResponse

    @POST("api/push/register")
    suspend fun registerPush(@Body body: PushRegisterRequest): OkResponse
}

class ApiClient(private val session: SessionManager) {

    private class Clients(val api: TextApi, val normal: OkHttpClient, val long: OkHttpClient)

    private val lock = Any()
    @Volatile
    private var cached: Pair<String, Clients>? = null

    private fun baseOkHttp(timeoutSec: Long): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSec, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .writeTimeout(timeoutSec, TimeUnit.SECONDS)
        .build()

    private fun authClient(timeoutSec: Long): OkHttpClient = baseOkHttp(timeoutSec).newBuilder()
        .addInterceptor { chain ->
            val token = session.cachedToken()
            val request = if (token != null) {
                chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }
        .build()

    private fun retrofit(base: OkHttpClient, url: String): TextApi = Retrofit.Builder()
        .baseUrl(url.trimEnd('/') + "/")
        .client(base)
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TextApi::class.java)

    private suspend fun currentUrl(): String {
        return BuildConfig.SERVER_URL.trimEnd('/')
    }

    private suspend fun clients(): Clients {
        val url = currentUrl()
        synchronized(lock) {
            cached?.let { if (it.first == url) return it.second }
            val normal = authClient(30)
            val long = authClient(300)
            val api = retrofit(normal, url)
            val built = Clients(api, normal, long)
            cached = url to built
            return built
        }
    }

    suspend fun <T> call(block: suspend (TextApi) -> T): T {
        return withContext(Dispatchers.IO) {
            try {
                block(clients().api)
            } catch (e: retrofit2.HttpException) {
                val body = e.response()?.errorBody()?.string()
                val error = runCatching { AppJson.decodeFromString<ErrorBody>(body ?: "") }.getOrNull()
                throw ApiException(
                    error?.message ?: error?.error ?: "server error (${e.code()})",
                    error?.error,
                )
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                throw ApiException("network error: ${e.message ?: "unreachable"}")
            }
        }
    }

    suspend fun uploadMedia(
        conversationId: String,
        file: File,
        onProgress: (Float) -> Unit,
    ): UploadResponse {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("conversationId", conversationId)
            .addFormDataPart(
                "file",
                file.name,
                ProgressRequestBody(file, "application/octet-stream".toMediaType(), onProgress),
            )
            .build()
        val request = Request.Builder()
            .url(currentUrl() + "/api/media")
            .post(body)
            .build()
        return withContext(Dispatchers.IO) {
            clients().long.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("upload failed (${resp.code})")
                AppJson.decodeFromString<UploadResponse>(resp.body?.string() ?: "{}")
            }
        }
    }

    suspend fun downloadMedia(mediaId: String): ByteArray {
        val request = Request.Builder()
            .url(currentUrl() + "/api/media/$mediaId")
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            clients().long.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("download failed (${resp.code})")
                resp.body?.bytes() ?: ByteArray(0)
            }
        }
    }
}

class ProgressRequestBody(
    private val file: File,
    private val contentType: okhttp3.MediaType?,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType(): okhttp3.MediaType? = contentType
    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var sent = 0L
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                sent += read
                onProgress(sent.toFloat() / file.length())
            }
        }
    }
}
