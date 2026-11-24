package com.example.data.telemetry

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Jetpack Credential Manager helper for Firebase Google Sign-In.
 * Always passes exactly one credential option per GetCredentialRequest for API 36 stability.
 */
object FirebaseGoogleAuthHelper {
  private const val TAG = "Auth"

  fun currentUser(): FirebaseUser? = runCatching { Firebase.auth.currentUser }.getOrNull()

  suspend fun attemptSilentSignIn(context: Context): FirebaseUser? {
    Firebase.auth.currentUser?.let { return it }
    val clientId = try {
      context.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
      return null
    }
    val credentialManager = CredentialManager.create(context)
    val googleIdOption = GetGoogleIdOption.Builder()
      .setFilterByAuthorizedAccounts(true)
      .setServerClientId(clientId)
      .setAutoSelectEnabled(true)
      .build()
    val request = GetCredentialRequest.Builder()
      .addCredentialOption(googleIdOption)
      .build()

    return try {
      val cred = credentialManager.getCredential(context, request).credential
      if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
        val authResult = Firebase.auth.signInWithCredential(
          GoogleAuthProvider.getCredential(token, null),
        ).await()
        authResult.user
      } else {
        null
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
  }

  suspend fun signInInteractively(activity: Activity): Result<FirebaseUser> {
    val clientId = try {
      activity.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
      return Result.failure(IllegalStateException("Google Sign-In configuration missing (default_web_client_id)."))
    }
    val credentialManager = CredentialManager.create(activity)
    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder()
      .addCredentialOption(signInOption)
      .build()

    return try {
      val cred = credentialManager.getCredential(activity, request).credential
      if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
        val authResult = Firebase.auth.signInWithCredential(
          GoogleAuthProvider.getCredential(token, null),
        ).await()
        val user = authResult.user
          ?: error("Firebase returned null user after Google Sign-In.")
        Result.success(user)
      } else {
        Result.failure(IllegalStateException("Unexpected Google credential type returned."))
      }
    } catch (e: GetCredentialCancellationException) {
      Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
      Result.failure(e)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Google Sign-In failed", e)
      Result.failure(e)
    }
  }

  suspend fun signOut(context: Context) {
    withContext(NonCancellable) {
      try {
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
      } catch (e: Exception) {
        Log.w(TAG, "Failed to clear credential state: ${e.message}")
      } finally {
        runCatching { Firebase.auth.signOut() }
      }
    }
  }
}
