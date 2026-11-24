package com.example.data.telemetry

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import com.example.R
import com.example.data.local.SystemIssueReportEntity
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.snapshots
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

enum class FirestoreOperationType(val value: String) {
  CREATE("create"),
  UPDATE("update"),
  DELETE("delete"),
  LIST("list"),
  GET("get"),
  WRITE("write"),
}

fun handleFirestoreError(
  exception: Throwable,
  operationType: FirestoreOperationType,
  path: String?,
): String {
  val causes = generateSequence(exception) { it.cause }
  val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
    ?: causes.firstOrNull { it !is CancellationException }
    ?: exception

  val currentUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
  val providerInfoList = currentUser?.providerData?.map { provider ->
    JSONObject().apply {
      put("providerId", provider.providerId)
      put("email", provider.email)
    }
  } ?: emptyList()

  val authInfoJson = JSONObject().apply {
    put("userId", currentUser?.uid)
    put("email", currentUser?.email)
    put("emailVerified", currentUser?.isEmailVerified)
    put("tenantId", currentUser?.tenantId)
    put("providerInfo", JSONArray(providerInfoList))
  }

  val errorInfoJson = JSONObject().apply {
    put("error", rootError.message ?: rootError.toString())
    put("operationType", operationType.value)
    put("path", path)
    put("authInfo", authInfoJson)
  }

  val jsonString = errorInfoJson.toString()
  Log.e("FirestoreError", "Firestore Error: $jsonString")
  return jsonString
}

@Keep
@IgnoreExtraProperties
data class CloudCustomSecurityQuestion(
  @com.google.firebase.firestore.DocumentId val id: String = "",
  val userId: String = "",
  val questionText: String = "",
  val locale: String = "en-US",
  val appVersion: String = "1.0",
  val createdAt: Timestamp? = null,
  val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class CloudDiagnosticReport(
  @com.google.firebase.firestore.DocumentId val id: String = "",
  val userId: String = "",
  val category: String = "USER_FEEDBACK",
  val errorCode: String = "NONE",
  val sanitizedSummary: String = "",
  val userFeedback: String = "",
  val healthSnapshot: String = "",
  val appVersion: String = "1.0",
  val androidSdk: Long = 34L,
  val deviceModel: String = "Android",
  val occurredAtMillis: Long = 0L,
  val createdAt: Timestamp? = null,
  val updatedAt: Timestamp? = null,
)

/**
 * Firestore-backed repository for:
 * 1) Custom security question prompt text (NEVER secret answers) to improve preset questions.
 * 2) User-submitted diagnostic reports & recorded system failures.
 *
 * Uses the named enterprise database ID from `R.string.firestore_database_id` exclusively.
 */
class CloudTelemetryRepository(
  private val db: FirebaseFirestore,
  private val appContext: Context,
) {
  constructor(context: Context) : this(
    db = FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id),
    ),
    appContext = context.applicationContext,
  )

  private val auth = Firebase.auth

  private fun requireUserId(): String =
    auth.currentUser?.uid
      ?: throw IllegalStateException("Please sign in with Google before sending data to the database.")

  suspend fun submitCustomSecurityQuestion(questionText: String): Result<String> {
    val trimmed = questionText.trim().take(160)
    if (trimmed.length < 5) {
      return Result.failure(IllegalArgumentException("Custom security question must be at least 5 characters."))
    }
    var docPath = "custom_security_questions"
    return try {
      val uid = requireUserId()
      val docRef = db.collection("custom_security_questions").document()
      docPath = docRef.path
      val localeTag = Locale.getDefault().toLanguageTag().ifBlank { "en-US" }.take(16)
      val version = SystemIssueRecorder.resolveAppVersion(appContext).take(32).ifBlank { "1.0" }
      val payload = mapOf(
        "userId" to uid,
        "questionText" to trimmed,
        "locale" to localeTag,
        "appVersion" to version,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
      )
      docRef.set(payload).await()
      Result.success(docRef.id)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, FirestoreOperationType.CREATE, docPath)
      Result.failure(e)
    }
  }

  suspend fun submitDiagnosticReport(
    category: IssueCategory,
    errorCode: String,
    sanitizedSummary: String,
    userFeedback: String,
    healthSnapshot: String,
    occurredAtMillis: Long = System.currentTimeMillis(),
  ): Result<String> {
    var docPath = "diagnostic_reports"
    return try {
      val uid = requireUserId()
      val docRef = db.collection("diagnostic_reports").document()
      docPath = docRef.path
      val cleanSummary = SystemIssueRecorder.sanitizeDiagnosticText(sanitizedSummary)
        .take(1200)
        .ifBlank { "Manual diagnostic report" }
      val cleanFeedback = userFeedback.trim().take(2000)
      val cleanSnapshot = SystemIssueRecorder.sanitizeDiagnosticText(healthSnapshot).take(800)
      val cleanCode = errorCode.trim().take(64).ifBlank { "GENERAL_DIAGNOSTIC" }
      val version = SystemIssueRecorder.resolveAppVersion(appContext).take(32).ifBlank { "1.0" }
      val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80).ifBlank { "Android" }
      val sdk = Build.VERSION.SDK_INT.coerceIn(24, 50).toLong()
      val safeOccurredAt = occurredAtMillis.coerceAtLeast(1L)

      val payload = mapOf(
        "userId" to uid,
        "category" to category.wireValue,
        "errorCode" to cleanCode,
        "sanitizedSummary" to cleanSummary,
        "userFeedback" to cleanFeedback,
        "healthSnapshot" to cleanSnapshot,
        "appVersion" to version,
        "androidSdk" to sdk,
        "deviceModel" to model,
        "occurredAtMillis" to safeOccurredAt,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
      )
      docRef.set(payload).await()
      Result.success(docRef.id)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, FirestoreOperationType.CREATE, docPath)
      Result.failure(e)
    }
  }

  suspend fun submitLocalIssueEntity(
    issue: SystemIssueReportEntity,
    userFeedbackOverride: String,
  ): Result<String> {
    val category = IssueCategory.entries.firstOrNull { it.wireValue == issue.category }
      ?: IssueCategory.USER_FEEDBACK
    return submitDiagnosticReport(
      category = category,
      errorCode = issue.errorCode,
      sanitizedSummary = issue.sanitizedSummary,
      userFeedback = userFeedbackOverride.ifBlank { issue.userFeedback },
      healthSnapshot = issue.healthSnapshot,
      occurredAtMillis = issue.occurredAtMillis,
    )
  }

  fun observeSubmittedReports(userId: String): Flow<List<CloudDiagnosticReport>> = flow {
    if (userId.isBlank() || auth.currentUser?.uid != userId) {
      emit(emptyList())
      return@flow
    }
    val path = "diagnostic_reports"
    emitAll(
      db.collection(path)
        .whereEqualTo("userId", userId)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(
            CloudDiagnosticReport::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          ).sortedByDescending { it.occurredAtMillis }
        }
        .catch { error ->
          val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, FirestoreOperationType.LIST, path)
          }
          throw error
        },
    )
  }
}
