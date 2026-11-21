package com.example.data.backup

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface DriveAuthOutcome {
  data class Authorized(val accessToken: String) : DriveAuthOutcome
  data class NeedsResolution(val pendingIntent: PendingIntent) : DriveAuthOutcome
  data class Error(val message: String) : DriveAuthOutcome
}

object GoogleDriveAuthHelper {
  const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
  const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

  private val driveScopes: List<Scope> = listOf(
    Scope(SCOPE_DRIVE_APPDATA),
    Scope(SCOPE_DRIVE_FILE),
  )

  private fun buildAuthRequest(): AuthorizationRequest =
    AuthorizationRequest.builder()
      .setRequestedScopes(driveScopes)
      .build()

  suspend fun authorize(activity: Activity): DriveAuthOutcome =
    suspendCancellableCoroutine { cont ->
      Identity.getAuthorizationClient(activity)
        .authorize(buildAuthRequest())
        .addOnSuccessListener { result ->
          if (!cont.isActive) return@addOnSuccessListener
          if (result.hasResolution()) {
            val intent = result.pendingIntent
            if (intent != null) {
              cont.resume(DriveAuthOutcome.NeedsResolution(intent))
            } else {
              cont.resume(DriveAuthOutcome.Error("Google Drive authorization intent unavailable"))
            }
          } else {
            val token = result.accessToken
            if (!token.isNullOrBlank()) {
              cont.resume(DriveAuthOutcome.Authorized(token))
            } else {
              cont.resume(DriveAuthOutcome.Error("Google Drive returned an empty access token"))
            }
          }
        }
        .addOnFailureListener { err ->
          if (cont.isActive) {
            cont.resume(
              DriveAuthOutcome.Error(
                err.localizedMessage ?: "Google Drive authorization failed on this device",
              ),
            )
          }
        }
    }

  suspend fun trySilentToken(context: Context): String? =
    suspendCancellableCoroutine { cont ->
      runCatching {
        Identity.getAuthorizationClient(context)
          .authorize(buildAuthRequest())
          .addOnSuccessListener { result ->
            if (!cont.isActive) return@addOnSuccessListener
            val token = if (!result.hasResolution()) result.accessToken else null
            cont.resume(token?.takeIf { it.isNotBlank() })
          }
          .addOnFailureListener {
            if (cont.isActive) cont.resume(null)
          }
      }.onFailure {
        if (cont.isActive) cont.resume(null)
      }
    }

  fun parseAuthorizationResult(context: Context, data: Intent?): String? {
    if (data == null) return null
    return runCatching {
      val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
      result.accessToken?.takeIf { it.isNotBlank() }
    }.getOrNull()
  }
}
