package com.example.data.backup

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class DriveAboutResponse(
  val user: DriveUserInfo? = null,
)

@Serializable
data class DriveUserInfo(
  val displayName: String? = null,
  val emailAddress: String? = null,
)

@Serializable
data class DriveFileMetadata(
  val id: String,
  val name: String,
  val size: String? = null,
  val modifiedTime: String? = null,
  val appProperties: Map<String, String> = emptyMap(),
)

@Serializable
data class DriveFileListResponse(
  val files: List<DriveFileMetadata> = emptyList(),
)

@Serializable
private data class DriveCreateFileMetadataBody(
  val name: String,
  val parents: List<String>? = null,
  val mimeType: String? = null,
  val appProperties: Map<String, String> = emptyMap(),
)

class DriveAuthExpiredException(message: String) : IOException(message)

class GoogleDriveApiClient(
  private val httpClient: OkHttpClient = defaultOkHttpClient(),
  private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
  private val uploadBaseUrl: String = DEFAULT_UPLOAD_BASE_URL,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  companion object {
    const val DEFAULT_API_BASE_URL = "https://www.googleapis.com/drive/v3"
    const val DEFAULT_UPLOAD_BASE_URL = "https://www.googleapis.com/upload/drive/v3"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val OCTET_MEDIA_TYPE = "application/octet-stream".toMediaType()

    fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(25, TimeUnit.SECONDS)
      .readTimeout(90, TimeUnit.SECONDS)
      .writeTimeout(120, TimeUnit.SECONDS)
      .build()
  }

  suspend fun getUserInfo(accessToken: String): DriveUserInfo? = withContext(Dispatchers.IO) {
    val url = "$apiBaseUrl/about".toHttpUrl().newBuilder()
      .addQueryParameter("fields", "user(displayName,emailAddress)")
      .build()
    val request = Request.Builder()
      .url(url)
      .header("Authorization", "Bearer $accessToken")
      .get()
      .build()
    httpClient.newCall(request).execute().use { response ->
      handleAuthError(response.code, response.message)
      if (!response.isSuccessful) return@withContext null
      val body = response.body?.string().orEmpty()
      if (body.isBlank()) return@withContext null
      json.decodeFromString<DriveAboutResponse>(body).user
    }
  }

  suspend fun listAppDataBackups(accessToken: String): List<DriveFileMetadata> = withContext(Dispatchers.IO) {
    val url = "$apiBaseUrl/files".toHttpUrl().newBuilder()
      .addQueryParameter("spaces", "appDataFolder")
      .addQueryParameter("fields", "files(id,name,size,modifiedTime,appProperties)")
      .addQueryParameter("orderBy", "modifiedTime desc")
      .addQueryParameter("q", "trashed = false")
      .addQueryParameter("pageSize", "30")
      .build()
    val request = Request.Builder()
      .url(url)
      .header("Authorization", "Bearer $accessToken")
      .get()
      .build()
    httpClient.newCall(request).execute().use { response ->
      handleAuthError(response.code, response.message)
      if (!response.isSuccessful) {
        val errText = response.body?.string().orEmpty()
        throw IOException("Google Drive list failed (${response.code}): ${errText.take(200)}")
      }
      val body = response.body?.string().orEmpty()
      if (body.isBlank()) return@withContext emptyList()
      json.decodeFromString<DriveFileListResponse>(body).files
    }
  }

  suspend fun uploadEncryptedArchive(
    accessToken: String,
    fileName: String,
    archiveFile: File,
    existingFileId: String? = null,
    appProperties: Map<String, String> = emptyMap(),
  ): DriveFileMetadata = withContext(Dispatchers.IO) {
    val metadataPayload = if (existingFileId == null) {
      DriveCreateFileMetadataBody(
        name = fileName,
        parents = listOf("appDataFolder"),
        mimeType = "application/octet-stream",
        appProperties = appProperties,
      )
    } else {
      DriveCreateFileMetadataBody(
        name = fileName,
        mimeType = "application/octet-stream",
        appProperties = appProperties,
      )
    }

    val multipart = MultipartBody.Builder()
      .setType("multipart/related".toMediaType())
      .addPart(json.encodeToString(metadataPayload).toRequestBody(JSON_MEDIA_TYPE))
      .addPart(archiveFile.asRequestBody(OCTET_MEDIA_TYPE))
      .build()

    val base = if (existingFileId == null) {
      "$uploadBaseUrl/files"
    } else {
      "$uploadBaseUrl/files/$existingFileId"
    }
    val url = base.toHttpUrl().newBuilder()
      .addQueryParameter("uploadType", "multipart")
      .addQueryParameter("fields", "id,name,size,modifiedTime,appProperties")
      .build()

    val builder = Request.Builder()
      .url(url)
      .header("Authorization", "Bearer $accessToken")
    val request = if (existingFileId == null) {
      builder.post(multipart).build()
    } else {
      builder.patch(multipart).build()
    }

    httpClient.newCall(request).execute().use { response ->
      handleAuthError(response.code, response.message)
      if (!response.isSuccessful) {
        val errText = response.body?.string().orEmpty()
        throw IOException("Google Drive upload failed (${response.code}): ${errText.take(220)}")
      }
      val body = response.body?.string().orEmpty()
      json.decodeFromString<DriveFileMetadata>(body)
    }
  }

  suspend fun downloadFileTo(
    accessToken: String,
    fileId: String,
    destinationFile: File,
  ): Unit = withContext(Dispatchers.IO) {
    val url = "$apiBaseUrl/files/$fileId".toHttpUrl().newBuilder()
      .addQueryParameter("alt", "media")
      .build()
    val request = Request.Builder()
      .url(url)
      .header("Authorization", "Bearer $accessToken")
      .get()
      .build()

    httpClient.newCall(request).execute().use { response ->
      handleAuthError(response.code, response.message)
      if (!response.isSuccessful) {
        val errText = response.body?.string().orEmpty()
        throw IOException("Google Drive download failed (${response.code}): ${errText.take(200)}")
      }
      val bodyStream = response.body?.byteStream()
        ?: throw IOException("Empty response stream from Google Drive")
      destinationFile.outputStream().use { out ->
        bodyStream.use { input -> input.copyTo(out) }
      }
    }
  }

  suspend fun deleteFile(
    accessToken: String,
    fileId: String,
  ): Unit = withContext(Dispatchers.IO) {
    val url = "$apiBaseUrl/files/$fileId"
    val request = Request.Builder()
      .url(url)
      .header("Authorization", "Bearer $accessToken")
      .delete()
      .build()
    httpClient.newCall(request).execute().use { response ->
      handleAuthError(response.code, response.message)
      if (!response.isSuccessful && response.code != 404) {
        val errText = response.body?.string().orEmpty()
        throw IOException("Google Drive delete failed (${response.code}): ${errText.take(200)}")
      }
    }
  }

  private fun handleAuthError(code: Int, message: String) {
    if (code == 401) {
      throw DriveAuthExpiredException("Google Drive session expired ($message). Tap Connect Google Drive to re-authorize.")
    }
  }
}
