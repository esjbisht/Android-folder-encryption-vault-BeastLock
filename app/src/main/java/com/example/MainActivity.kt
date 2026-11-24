package com.example

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.crypto.BiometricAuthResult
import com.example.crypto.BiometricHelper
import com.example.data.backup.DriveAuthOutcome
import com.example.data.backup.GoogleDriveAuthHelper
import com.example.data.storage.MigrationExportFormat
import com.example.quicksettings.LockVaultTileService
import com.example.quicksettings.PanicModeTileService
import com.example.security.PanicShakeMonitor
import com.example.ui.AuditLogRoute
import com.example.ui.CloudBackupRoute
import com.example.ui.DashboardRoute
import com.example.ui.DiagnosticsSupportRoute
import com.example.ui.FolderDetailRoute
import com.example.ui.SecuritySettingsRoute
import com.example.ui.StorageAnalyticsRoute
import com.example.ui.VaultSessionState
import com.example.ui.VaultViewModel
import com.example.ui.components.CloudBackupProgressDialog
import com.example.ui.components.EncryptionProgressStrip
import com.example.ui.components.IntegrityDiagnosticDialog
import com.example.ui.components.MigrationProgressDialog
import com.example.ui.components.MigrationResultDialog
import com.example.ui.components.MigrationWizardDialog
import com.example.ui.components.OrphanCleanupDialog
import com.example.ui.components.SecondaryAuthChallengeDialog
import com.example.ui.components.SelectiveLockFilesDialog
import com.example.ui.components.StealthVaultDrawerSheet
import com.example.ui.components.VaultCircularProgressIndicator
import com.example.ui.screens.AuditLogScreen
import com.example.ui.screens.CloudBackupScreen
import com.example.ui.screens.DiagnosticsSupportScreen
import com.example.ui.screens.FolderDetailScreen
import com.example.ui.screens.OnboardingLockSetupScreen
import com.example.ui.screens.SecuritySettingsScreen
import com.example.ui.screens.StorageAnalyticsScreen
import com.example.ui.screens.VaultAuthGateScreen
import com.example.ui.screens.VaultDashboardScreen
import com.example.ui.theme.BeastLockTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
  private var activeViewModel: VaultViewModel? = null

  override fun onUserInteraction() {
    super.onUserInteraction()
    activeViewModel?.onUserInteraction()
  }

  override fun onDestroy() {
    activeViewModel = null
    super.onDestroy()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val viewModel: VaultViewModel = viewModel()
      activeViewModel = viewModel
      val state by viewModel.uiState.collectAsStateWithLifecycle()

      val context = LocalContext.current
      val hapticFeedback = LocalHapticFeedback.current
      val lifecycleOwner = LocalLifecycleOwner.current
      val biometricHelper = remember(this@MainActivity) { BiometricHelper(this@MainActivity) }
      val rootCoroutineScope = rememberCoroutineScope()

      val latestPanicAction by rememberUpdatedState {
        viewModel.triggerPanicLock(source = "SHAKE_GESTURE")
      }
      val panicMonitor = remember(context) {
        PanicShakeMonitor(
          context = context,
          initialSensitivity = state.securitySettings.panicSensitivity,
          onPanicShake = { latestPanicAction() },
        )
      }

      LaunchedEffect(state.securitySettings.panicSensitivity) {
        panicMonitor.updateSensitivity(state.securitySettings.panicSensitivity)
      }

      LaunchedEffect(viewModel) {
        viewModel.panicEvents.collect { exitToDeviceHome ->
          hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
          if (exitToDeviceHome) {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
              addCategory(Intent.CATEGORY_HOME)
              flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            runCatching { startActivity(homeIntent) }
          }
        }
      }

      DisposableEffect(
        lifecycleOwner,
        state.sessionState,
        state.securitySettings.panicModeEnabled,
      ) {
        val shouldListen = state.sessionState == VaultSessionState.Unlocked &&
          state.securitySettings.panicModeEnabled
        if (shouldListen && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
          panicMonitor.start()
        } else {
          panicMonitor.stop()
        }

        val observer = LifecycleEventObserver { _, event ->
          when (event) {
            Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
            Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
            Lifecycle.Event.ON_RESUME -> if (shouldListen) panicMonitor.start()
            Lifecycle.Event.ON_PAUSE -> panicMonitor.stop()
            else -> Unit
          }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
          biometricHelper.cancelAuthentication()
          panicMonitor.stop()
          lifecycleOwner.lifecycle.removeObserver(observer)
        }
      }

      var biometricFallbackTrigger by remember { mutableIntStateOf(0) }
      var biometricFallbackReason by remember { mutableStateOf<String?>(null) }

      val negativeButtonLabel = when {
        state.securitySettings.hasPatternLock &&
          (!state.securitySettings.hasPinCredential || state.securitySettings.preferPatternFallback) ->
          getString(R.string.biometric_negative_use_pattern)
        state.securitySettings.hasPatternLock ->
          getString(R.string.biometric_negative_use_pattern_or_pin)
        else -> getString(R.string.biometric_negative_use_pin)
      }

      val triggerUnlockBiometric: () -> Unit = {
        rootCoroutineScope.launch {
          val keyCryptoObject = viewModel.prepareBiometricKeyCryptoObject()
          biometricHelper.authenticateBeforeKeyAccess(
            title = getString(R.string.biometric_prompt_title),
            subtitle = getString(R.string.biometric_prompt_subtitle),
            negativeButtonText = negativeButtonLabel,
            prepareCryptoObject = { keyCryptoObject },
            onAuthAttemptFailed = { reason ->
              viewModel.recordFailedBiometricAttempt(reason, "BeastLock Gate")
            },
          ) { result ->
            when (result) {
              is BiometricAuthResult.Success -> {
                biometricFallbackReason = null
                viewModel.onBiometricUnlockSuccess(result.cryptoObject)
              }
              BiometricAuthResult.FallbackToPin -> {
                biometricFallbackReason = if (state.securitySettings.hasPatternLock) {
                  "Biometric prompt switched to secondary auth — draw your 3×3 Pattern or enter PIN."
                } else {
                  null
                }
                biometricFallbackTrigger++
              }
              is BiometricAuthResult.Error -> {
                biometricFallbackReason = if (state.securitySettings.hasPatternLock) {
                  "Biometrics unavailable (${result.message}). Draw your 3×3 Pattern or enter PIN."
                } else {
                  result.message
                }
                biometricFallbackTrigger++
              }
              BiometricAuthResult.Cancelled -> Unit
            }
          }
        }
      }

      val authorizeActionBiometric: (
        folderName: String?,
        isUnlocking: Boolean,
        title: String?,
        subtitle: String?,
        fallbackToSecondary: () -> Unit,
        onCancelled: () -> Unit,
        onAuthorized: () -> Unit,
      ) -> Unit = { folderName, isUnlocking, title, subtitle, fallbackToSecondary, onCancelled, onAuthorized ->
        if (state.securitySettings.biometricEnabled && state.biometricAvailability.canAuthenticate) {
          rootCoroutineScope.launch {
            val keyCryptoObject = viewModel.prepareBiometricKeyCryptoObject()
            biometricHelper.authenticateForFolderAccess(
              folderName = folderName,
              isUnlocking = isUnlocking,
              title = title,
              subtitle = subtitle,
              negativeButtonText = negativeButtonLabel,
              prepareCryptoObject = { keyCryptoObject },
              onAuthAttemptFailed = { reason ->
                viewModel.recordFailedBiometricAttempt(
                  reason,
                  folderName ?: "Vault Security Action",
                )
              },
            ) { result ->
              when (result) {
                is BiometricAuthResult.Success -> onAuthorized()
                BiometricAuthResult.FallbackToPin,
                is BiometricAuthResult.Error -> fallbackToSecondary()
                BiometricAuthResult.Cancelled -> onCancelled()
              }
            }
          }
        } else {
          fallbackToSecondary()
        }
      }

      if (state.sessionState != VaultSessionState.Unlocked) {
        LaunchedEffect(state.securitySettings.alwaysBlockScreenshots) {
          if (state.securitySettings.alwaysBlockScreenshots) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
          } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
          }
        }
      }

      BeastLockTheme(
        themeMode = state.securitySettings.themeMode,
        dynamicColor = state.securitySettings.dynamicColor,
      ) {
        when (state.sessionState) {
          VaultSessionState.Loading -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
              VaultCircularProgressIndicator()
            }
          }
          VaultSessionState.NeedsOnboarding -> {
            OnboardingLockSetupScreen(
              biometricAvailability = state.biometricAvailability,
              themeMode = state.securitySettings.themeMode,
              onSelectThemeMode = viewModel::setThemeMode,
              onCompleteSetup = { lockType, pin, patternNodes, enableBio, q1, a1, q2, a2, shareCustom, customPrompts, externalUri ->
                viewModel.completeOnboardingWithChoice(
                  lockType = lockType,
                  pin = pin,
                  patternNodes = patternNodes,
                  enableBiometrics = enableBio,
                  question1 = q1,
                  answer1 = a1,
                  question2 = q2,
                  answer2 = a2,
                  shareCustomQuestionSuggestions = shareCustom,
                  customQuestionsToQueue = customPrompts,
                  externalDualKeyUri = externalUri,
                )
              },
            )
          }
          VaultSessionState.Locked -> {
            VaultAuthGateScreen(
              settings = state.securitySettings,
              biometricAvailability = state.biometricAvailability,
              authError = state.authError,
              recoveryError = state.recoveryError,
              biometricFallbackTrigger = biometricFallbackTrigger,
              biometricFallbackReason = biometricFallbackReason,
              onPinSubmitted = viewModel::unlockWithPin,
              onPatternSubmitted = viewModel::unlockWithPattern,
              onRequestBiometricPrompt = triggerUnlockBiometric,
              onRecoverCredentials = { a1, a2, newPin, newPattern, targetType ->
                viewModel.recoverWithSecurityQuestions(
                  answer1 = a1,
                  answer2 = a2,
                  newPin = newPin,
                  newPatternNodes = newPattern,
                  newLockType = targetType,
                )
              },
              onSelectThemeMode = viewModel::setThemeMode,
              onClearErrors = {
                biometricFallbackReason = null
                viewModel.clearMessages()
              },
            )
          }
          VaultSessionState.Unlocked -> {
            VaultUnlockedAppShell(
              activity = this@MainActivity,
              viewModel = viewModel,
              hasAccelerometer = panicMonitor.hasAccelerometer,
              onAuthorizeBiometric = authorizeActionBiometric,
            )
          }
        }

        state.cloudBackupProgress?.let { CloudBackupProgressDialog(it) }
        if (state.isAutoLockReencrypting) {
          androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("Auto-Locking Folders...") },
            text = { Text("Re-encrypting unlocked folders with AES-256-GCM.") },
            confirmButton = {},
          )
        }
      }
    }
  }
}

private data class TopNavItem(
  val route: Any,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
)

private data class PendingMigrationRequest(
  val folderIds: List<String>,
  val format: MigrationExportFormat,
  val removeOriginalsAfterSuccess: Boolean,
)

private data class PendingSecondaryAuthAction(
  val title: String,
  val description: String,
  val actionLabel: String,
  val onAuthorized: () -> Unit,
  val onDismiss: () -> Unit = {},
)

@Composable
private fun VaultUnlockedAppShell(
  activity: FragmentActivity,
  viewModel: VaultViewModel,
  hasAccelerometer: Boolean,
  onAuthorizeBiometric: (
    folderName: String?,
    isUnlocking: Boolean,
    title: String?,
    subtitle: String?,
    fallbackToSecondary: () -> Unit,
    onCancelled: () -> Unit,
    onAuthorized: () -> Unit,
  ) -> Unit,
) {
  val authorizeActionBiometric: (
    folderName: String?,
    isUnlocking: Boolean,
    title: String?,
    subtitle: String?,
    fallbackToSecondary: () -> Unit,
    onAuthorized: () -> Unit,
  ) -> Unit = { folderName, isUnlocking, title, subtitle, fallbackToSecondary, onAuthorized ->
    onAuthorizeBiometric(folderName, isUnlocking, title, subtitle, fallbackToSecondary, {}, onAuthorized)
  }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentDestination = navBackStackEntry?.destination
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var pendingSecondaryAuthAction by remember { mutableStateOf<PendingSecondaryAuthAction?>(null) }
  var showOrphanCleanupDialog by remember { mutableStateOf(false) }
  var showIntegrityDiagnosticDialog by remember { mutableStateOf(false) }
  var showMigrationWizard by remember { mutableStateOf(false) }
  var activeMigrationRequest by remember { mutableStateOf<PendingMigrationRequest?>(null) }
  var hasRequestedNotificationPermission by remember { mutableStateOf(false) }

  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { _ -> }

  LaunchedEffect(state.encryptionProgress != null) {
    if (state.encryptionProgress != null &&
      !hasRequestedNotificationPermission &&
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    ) {
      hasRequestedNotificationPermission = true
      val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }

  val activeDetailFolderId = remember(navBackStackEntry) {
    if (navBackStackEntry?.destination?.hasRoute<FolderDetailRoute>() == true) {
      runCatching { navBackStackEntry?.toRoute<FolderDetailRoute>()?.folderId }.getOrNull()
    } else {
      null
    }
  }
  val isViewingStealthFolder = activeDetailFolderId != null &&
    state.folders.any { it.id == activeDetailFolderId && it.isStealthHidden }

  val shouldBlockScreenshots = state.securitySettings.alwaysBlockScreenshots ||
    state.isStealthRevealed ||
    isViewingStealthFolder ||
    state.securePreview != null
  LaunchedEffect(activity, shouldBlockScreenshots) {
    if (shouldBlockScreenshots) {
      activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
      activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }
  DisposableEffect(activity) {
    onDispose {
      activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }

  LaunchedEffect(state.isStealthRevealed, isViewingStealthFolder) {
    if (!state.isStealthRevealed && isViewingStealthFolder) {
      navController.popBackStack<DashboardRoute>(inclusive = false)
    }
  }

  LaunchedEffect(state.pendingStealthTriggerLabel) {
    val trigger = state.pendingStealthTriggerLabel ?: return@LaunchedEffect
    val authHint = when {
      state.securitySettings.hasPatternLock && state.securitySettings.hasPinCredential ->
        "Draw your 3×3 Pattern or enter your ${state.securitySettings.pinLength}-digit Master PIN"
      state.securitySettings.hasPatternLock ->
        "Draw your 3×3 Pattern"
      else ->
        "Enter your ${state.securitySettings.pinLength}-digit Master PIN"
    }
    onAuthorizeBiometric(
      "Stealth Private Vault",
      false,
      "Unlock Stealth Private Vault",
      "Verify with fingerprint or facial recognition to reveal hidden Private Vault folders",
      {
        pendingSecondaryAuthAction = PendingSecondaryAuthAction(
          title = "Unlock Stealth Private Vault",
          description = "$authHint to reveal hidden Private Vault folders.",
          actionLabel = "Stealth Private Vault ($trigger)",
          onAuthorized = { viewModel.onStealthAuthVerified() },
          onDismiss = { viewModel.dismissStealthAuthChallenge() },
        )
      },
      {
        viewModel.dismissStealthAuthChallenge()
      },
      {
        viewModel.onStealthAuthVerified()
      },
    )
  }

  val driveAuthLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartIntentSenderForResult(),
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      val token = GoogleDriveAuthHelper.parseAuthorizationResult(context, result.data)
      if (!token.isNullOrBlank()) {
        viewModel.onGoogleDriveAuthorized(token)
      } else {
        viewModel.reportGoogleDriveAuthError("Google Drive authorization did not return an access token.")
      }
    } else {
      viewModel.reportGoogleDriveAuthError("Google Drive authorization cancelled.")
    }
  }

  val startGoogleDriveAuth: () -> Unit = {
    coroutineScope.launch {
      when (val outcome = GoogleDriveAuthHelper.authorize(activity)) {
        is DriveAuthOutcome.Authorized -> viewModel.onGoogleDriveAuthorized(outcome.accessToken)
        is DriveAuthOutcome.NeedsResolution -> {
          driveAuthLauncher.launch(
            IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build(),
          )
        }
        is DriveAuthOutcome.Error -> viewModel.reportGoogleDriveAuthError(outcome.message)
      }
    }
  }

  LaunchedEffect(viewModel) {
    viewModel.driveReauthRequired.collect {
      startGoogleDriveAuth()
    }
  }

  val migrateTreeLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree(),
  ) { uri ->
    val req = activeMigrationRequest
    activeMigrationRequest = null
    if (uri != null && req != null) {
      viewModel.exportDecryptedMigration(
        folderIds = req.folderIds,
        format = MigrationExportFormat.FOLDER_TREE,
        destinationUri = uri,
        removeOriginalsAfterSuccess = req.removeOriginalsAfterSuccess,
      )
    }
  }

  val migrateZipLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/zip"),
  ) { uri ->
    val req = activeMigrationRequest
    activeMigrationRequest = null
    if (uri != null && req != null) {
      viewModel.exportDecryptedMigration(
        folderIds = req.folderIds,
        format = MigrationExportFormat.PLAIN_ZIP,
        destinationUri = uri,
        removeOriginalsAfterSuccess = req.removeOriginalsAfterSuccess,
      )
    }
  }

  val launchDestinationPicker: (PendingMigrationRequest) -> Unit = { req ->
    activeMigrationRequest = req
    when (req.format) {
      MigrationExportFormat.FOLDER_TREE -> migrateTreeLauncher.launch(null)
      MigrationExportFormat.PLAIN_ZIP -> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        migrateZipLauncher.launch("BeastLock_Export_$stamp.zip")
      }
    }
  }

  LaunchedEffect(state.statusMessage, state.errorMessage) {
    val msg = state.errorMessage ?: state.statusMessage
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearMessages()
    }
  }

  val topItems = remember {
    listOf(
      TopNavItem(
        route = DashboardRoute,
        label = "Vault",
        selectedIcon = Icons.Rounded.Folder,
        unselectedIcon = Icons.Outlined.Folder,
        testTag = "nav_vault",
      ),
      TopNavItem(
        route = StorageAnalyticsRoute,
        label = "Usage",
        selectedIcon = Icons.Rounded.PieChart,
        unselectedIcon = Icons.Outlined.PieChart,
        testTag = "nav_usage",
      ),
      TopNavItem(
        route = CloudBackupRoute,
        label = "Cloud",
        selectedIcon = Icons.Rounded.CloudUpload,
        unselectedIcon = Icons.Outlined.CloudUpload,
        testTag = "nav_cloud",
      ),
      TopNavItem(
        route = AuditLogRoute,
        label = "Audit",
        selectedIcon = Icons.Rounded.History,
        unselectedIcon = Icons.Outlined.History,
        testTag = "nav_audit",
      ),
      TopNavItem(
        route = SecuritySettingsRoute,
        label = "Security",
        selectedIcon = Icons.Rounded.Security,
        unselectedIcon = Icons.Outlined.Security,
        testTag = "nav_security",
      ),
    )
  }

  val isTopDestination = currentDestination?.let { dest ->
    dest.hasRoute<DashboardRoute>() ||
      dest.hasRoute<StorageAnalyticsRoute>() ||
      dest.hasRoute<CloudBackupRoute>() ||
      dest.hasRoute<AuditLogRoute>() ||
      dest.hasRoute<SecuritySettingsRoute>()
  } ?: true

  val navigateTop: (Any) -> Unit = { route ->
    navController.navigate(route) {
      popUpTo(navController.graph.findStartDestination().id) {
        saveState = true
      }
      launchSingleTop = true
      restoreState = true
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useRail = maxWidth >= 600.dp

    Scaffold(
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      snackbarHost = {
        SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.navigationBarsPadding(),
        )
      },
      bottomBar = {
        if (!useRail && isTopDestination) {
          NavigationBar(modifier = Modifier.navigationBarsPadding()) {
            topItems.forEach { item ->
              val selected = when (item.route) {
                DashboardRoute -> currentDestination?.hasRoute<DashboardRoute>() == true
                StorageAnalyticsRoute -> currentDestination?.hasRoute<StorageAnalyticsRoute>() == true
                CloudBackupRoute -> currentDestination?.hasRoute<CloudBackupRoute>() == true
                AuditLogRoute -> currentDestination?.hasRoute<AuditLogRoute>() == true
                SecuritySettingsRoute -> currentDestination?.hasRoute<SecuritySettingsRoute>() == true
                else -> false
              }
              NavigationBarItem(
                selected = selected,
                onClick = { navigateTop(item.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                    contentDescription = item.label,
                  )
                },
                label = { Text(item.label) },
                modifier = Modifier.testTag(item.testTag),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        if (useRail && isTopDestination) {
          NavigationRail(
            modifier = Modifier
              .statusBarsPadding()
              .navigationBarsPadding(),
          ) {
            topItems.forEach { item ->
              val selected = when (item.route) {
                DashboardRoute -> currentDestination?.hasRoute<DashboardRoute>() == true
                StorageAnalyticsRoute -> currentDestination?.hasRoute<StorageAnalyticsRoute>() == true
                CloudBackupRoute -> currentDestination?.hasRoute<CloudBackupRoute>() == true
                AuditLogRoute -> currentDestination?.hasRoute<AuditLogRoute>() == true
                SecuritySettingsRoute -> currentDestination?.hasRoute<SecuritySettingsRoute>() == true
                else -> false
              }
              NavigationRailItem(
                selected = selected,
                onClick = { navigateTop(item.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                    contentDescription = item.label,
                  )
                },
                label = { Text(item.label) },
                modifier = Modifier.testTag(item.testTag),
              )
            }
          }
        }

        val openFolderWithAuthGate: (String) -> Unit = { folderId ->
          val targetFolder = state.folders.find { it.id == folderId }
          if (targetFolder != null && (targetFolder.requireAuthOnOpen || targetFolder.isLocked || targetFolder.encryptedFileCount > 0)) {
            authorizeActionBiometric(
              targetFolder.name,
              false,
              null,
              null,
              {
                val authHint = when {
                  state.securitySettings.hasPatternLock && state.securitySettings.hasPinCredential ->
                    "Draw your 3×3 Pattern or enter your ${state.securitySettings.pinLength}-digit Master PIN"
                  state.securitySettings.hasPatternLock ->
                    "Draw your 3×3 Pattern"
                  else ->
                    "Enter your ${state.securitySettings.pinLength}-digit Master PIN"
                }
                pendingSecondaryAuthAction = PendingSecondaryAuthAction(
                  title = "Authenticate Folder Access",
                  description = "$authHint to open \"${targetFolder.name}\".",
                  actionLabel = targetFolder.name,
                  onAuthorized = { navController.navigate(FolderDetailRoute(folderId)) },
                )
              },
              { navController.navigate(FolderDetailRoute(folderId)) },
            )
          } else {
            navController.navigate(FolderDetailRoute(folderId))
          }
        }

        val hasTopProgressStrip = state.encryptionProgress != null
        val topScreenModifier = if (hasTopProgressStrip) Modifier else Modifier.statusBarsPadding()

        Column(modifier = Modifier.weight(1f)) {
          AnimatedVisibility(
            visible = hasTopProgressStrip,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
          ) {
            state.encryptionProgress?.let { activeProgress ->
              EncryptionProgressStrip(
                progress = activeProgress,
                deferredAutoLockPending = state.deferredAutoLockPending,
                modifier = Modifier
                  .fillMaxWidth()
                  .statusBarsPadding(),
              )
            }
          }

          NavHost(
            navController = navController,
            startDestination = DashboardRoute,
            modifier = Modifier.weight(1f),
          ) {
            composable<DashboardRoute> {
              VaultDashboardScreen(
                state = state,
                onSelectFilter = viewModel::setFilter,
                onSelectSortOrder = viewModel::setFolderSortOrder,
                onSearchQueryChange = viewModel::setSearchQuery,
                onClearSearchAndFilters = viewModel::clearDashboardSearchAndFilters,
                onPickerLaunching = viewModel::markStealthPickerInFlight,
                onPickDeviceFolder = viewModel::addDeviceFolder,
                onPreviewPickedFolder = viewModel::previewPickedDeviceFolder,
                onUpdateFolderPickerOptions = viewModel::updateFolderPickerPreviewOptions,
                onConfirmFolderPickerPreview = viewModel::confirmFolderPickerPreview,
                onDismissFolderPickerPreview = viewModel::dismissFolderPickerPreview,
                onRegrantFolderPermission = viewModel::regrantDeviceFolderPermission,
                onDecryptAnyDeviceFolder = { uri ->
                  authorizeActionBiometric(
                    null,
                    true,
                    "Authorize Folder Decryption",
                    "Verify with fingerprint or facial recognition to decrypt files in the selected device folder",
                    {
                      pendingSecondaryAuthAction = PendingSecondaryAuthAction(
                        title = "Authorize Folder Decryption",
                        description = "Draw your 3×3 Pattern or enter Master PIN to decrypt files in the selected device folder.",
                        actionLabel = "Decrypt Any Device Folder",
                        onAuthorized = { viewModel.decryptAnyDeviceFolder(uri) },
                      )
                    },
                    { viewModel.decryptAnyDeviceFolder(uri) },
                  )
                },
                onCreatePrivateFolder = viewModel::createPrivateFolder,
                onOpenFolder = openFolderWithAuthGate,
                onToggleLockFolder = { folder ->
                  if (folder.isLocked) {
                    if (folder.requireBiometricPerUnlock) {
                      authorizeActionBiometric(
                        folder.name,
                        true,
                        null,
                        null,
                        {
                          pendingSecondaryAuthAction = PendingSecondaryAuthAction(
                            title = "Authorize Folder Unlock",
                            description = "Draw your 3×3 Pattern or enter Master PIN to decrypt \"${folder.name}\".",
                            actionLabel = folder.name,
                            onAuthorized = { viewModel.unlockFolder(folder.id) },
                          )
                        },
                        { viewModel.unlockFolder(folder.id) },
                      )
                    } else {
                      viewModel.unlockFolder(folder.id)
                    }
                  } else {
                    viewModel.lockFolder(folder.id, reloadDetail = false)
                  }
                },
                onRemoveFolder = { folderId, decryptLockedSafFirst ->
                  viewModel.removeFolder(folderId, decryptLockedSafFirst = decryptLockedSafFirst)
                },
                onEnterSelectionMode = viewModel::enterFolderSelectionMode,
                onToggleFolderSelection = viewModel::toggleFolderSelection,
                onSelectAllFilteredFolders = viewModel::selectAllFilteredFolders,
                onClearSelection = viewModel::clearFolderSelection,
                onEncryptSelectedFolders = viewModel::encryptSelectedFolders,
                onDecryptSelectedFolders = {
                  val selectedLocked = state.folders.filter {
                    it.id in state.selectedFolderIds && (it.isLocked || it.encryptedFileCount > 0)
                  }
                  if (selectedLocked.any { it.requireBiometricPerUnlock }) {
                    authorizeActionBiometric(
                      null,
                      true,
                      "Unlock ${selectedLocked.size} Encrypted Folder(s)",
                      "Verify with fingerprint or facial recognition to decrypt selected folders",
                      {
                        pendingSecondaryAuthAction = PendingSecondaryAuthAction(
                          title = "Authorize Batch Unlock",
                          description = "Draw your 3×3 Pattern or enter Master PIN to decrypt ${selectedLocked.size} selected folder(s).",
                          actionLabel = "Batch Folder Unlock",
                          onAuthorized = { viewModel.decryptSelectedFolders() },
                        )
                      },
                      { viewModel.decryptSelectedFolders() },
                    )
                  } else {
                    viewModel.decryptSelectedFolders()
                  }
                },
                onReencryptSelectedFolders = { includeUnlocked ->
                  authorizeActionBiometric(
                    null,
                    false,
                    "Authorize Key Rotation",
                    "Verify with fingerprint or facial recognition to rotate folder encryption keys",
                    {
                      pendingSecondaryAuthAction = PendingSecondaryAuthAction(
                        title = "Authorize Key Rotation",
                        description = "Draw your 3×3 Pattern or enter your Master Vault PIN to authorize key rotation and re-encryption.",
                        actionLabel = "Batch Key Rotation",
                        onAuthorized = { viewModel.reencryptSelectedFolders(includeUnlocked) },
                      )
                    },
                    { viewModel.reencryptSelectedFolders(includeUnlocked) },
                  )
                },
                onRemoveSelectedFolders = { decryptLockedSafFirst ->
                  viewModel.removeSelectedFolders(decryptLockedSafFirst = decryptLockedSafFirst)
                },
                onToggleFolderAutoCloudSync = viewModel::setFolderAutoCloudBackupSync,
                onSetSelectedAutoCloudSync = viewModel::setSelectedFoldersAutoCloudBackupSync,
                onBatchRenameFolders = viewModel::batchRenameFolders,
                onOpenStorageCharts = { navigateTop(StorageAnalyticsRoute) },
                onOpenDiagnostics = { navController.navigate(DiagnosticsSupportRoute) },
                onTriggerStealthGesture = { viewModel.requestRevealStealthVault("SHIELD_GESTURE") },
                onLockVaultNow = { viewModel.lockVaultNow() },
                onSelectThemeMode = viewModel::setThemeMode,
                modifier = topScreenModifier,
              )
            }

            composable<StorageAnalyticsRoute> {
              StorageAnalyticsScreen(
                state = state,
                onOpenFolder = openFolderWithAuthGate,
                onLockFolder = { folderId ->
                  viewModel.lockFolder(folderId)
                },
                onNavigateToVault = { navigateTop(DashboardRoute) },
                onScanOrphans = { viewModel.scanOrphanedFiles(showCleanMessage = true) },
                onOpenOrphanCleanup = {
                  viewModel.scanOrphanedFiles()
                  showOrphanCleanupDialog = true
                },
                onOpenMigrateOut = { showMigrationWizard = true },
                onOpenIntegrityDiagnostic = {
                  showIntegrityDiagnosticDialog = true
                  if (!state.isScanningIntegrity && state.integrityDiagnosticReport == null) {
                    viewModel.runIntegrityDiagnosticScan()
                  }
                },
                modifier = topScreenModifier,
              )
            }

            composable<FolderDetailRoute> { backStackEntry ->
              val route = backStackEntry.toRoute<FolderDetailRoute>()
              val folder = state.folders.find { it.id == route.folderId }
              LaunchedEffect(route.folderId) {
                viewModel.recordFolderAccessed(route.folderId)
              }
              FolderDetailScreen(
                folder = folder,
                files = state.activeFolderFiles,
                isLoadingFiles = state.isLoadingFiles,
                securePreview = state.securePreview,
                isPreviewLoading = state.isPreviewLoading,
                encryptionProgress = state.encryptionProgress,
                isFolderBusy = state.isFolderBusy(route.folderId),
                hasTopProgressStrip = hasTopProgressStrip,
                onBack = {
                  if (navController.currentBackStackEntry?.destination?.hasRoute<FolderDetailRoute>() == true) {
                    navController.popBackStack()
                  }
                },
                onRefresh = { viewModel.loadFolderFiles(route.folderId) },
                onImportDocuments = { uris -> viewModel.importFiles(route.folderId, uris) },
                onLockFolder = { viewModel.lockFolder(route.folderId, reloadDetail = true) },
                onSelectiveLockFolder = { viewModel.requestLockFolderWithSelection(route.folderId, reloadDetail = true) },
                onUnlockFolder = {
                  if (folder?.requireBiometricPerUnlock == true) {
                    authorizeActionBiometric(
                      folder.name,
                      true,
                      null,
                      null,
                      {
                        pendingSecondaryAuthAction = PendingSecondaryAuthAction(
                          title = "Authorize Folder Unlock",
                          description = "Draw your 3×3 Pattern or enter Master PIN to decrypt \"${folder.name}\".",
                          actionLabel = folder.name,
                          onAuthorized = { viewModel.unlockFolder(route.folderId, reloadDetail = true) },
                        )
                      },
                      { viewModel.unlockFolder(route.folderId, reloadDetail = true) },
                    )
                  } else {
                    viewModel.unlockFolder(route.folderId, reloadDetail = true)
                  }
                },
                onEncryptFiles = { fileIds ->
                  viewModel.lockFolder(route.folderId, reloadDetail = true, selectedFileIds = fileIds)
                },
                onDecryptFiles = { fileIds ->
                  if (folder?.requireBiometricPerUnlock == true) {
                    authorizeActionBiometric(
                      folder.name,
                      true,
                      "Decrypt File(s) in \"${folder.name}\"",
                      "Verify with fingerprint or facial recognition to decrypt ${fileIds.size} selected file(s)",
                      {
                        pendingSecondaryAuthAction = PendingSecondaryAuthAction(
                          title = "Authorize File Decryption",
                          description = "Draw your 3×3 Pattern or enter Master PIN to decrypt ${fileIds.size} selected file(s) in \"${folder.name}\".",
                          actionLabel = folder.name,
                          onAuthorized = {
                            viewModel.decryptFilesInFolder(route.folderId, fileIds, reloadDetail = true)
                          },
                        )
                      },
                      { viewModel.decryptFilesInFolder(route.folderId, fileIds, reloadDetail = true) },
                    )
                  } else {
                    viewModel.decryptFilesInFolder(route.folderId, fileIds, reloadDetail = true)
                  }
                },
                onRemoveFiles = { fileIds ->
                  viewModel.removeFilesInFolder(route.folderId, fileIds, reloadDetail = true)
                },
                onUpdateOptions = { reqBio, obf ->
                  viewModel.updateFolderOptions(route.folderId, reqBio, obf)
                },
                onToggleAutoCloudSync = { enabled ->
                  viewModel.setFolderAutoCloudBackupSync(route.folderId, enabled)
                },
                onBatchRenameFiles = { renameMap ->
                  viewModel.batchRenameFiles(route.folderId, renameMap)
                },
                onPeekFile = { item ->
                  if (item.isEncrypted && folder?.requireBiometricPerUnlock == true && !folder.requireAuthOnOpen) {
                    authorizeActionBiometric(
                      folder.name,
                      false,
                      "Preview \"${item.displayName}\"",
                      "Verify with fingerprint or facial recognition to inspect encrypted file contents",
                      {
                        pendingSecondaryAuthAction = PendingSecondaryAuthAction(
                          title = "Authorize Encrypted Preview",
                          description = "Draw your 3×3 Pattern or enter Master PIN to preview \"${item.displayName}\".",
                          actionLabel = item.displayName,
                          onAuthorized = { viewModel.peekFile(item) },
                        )
                      },
                      { viewModel.peekFile(item) },
                    )
                  } else {
                    viewModel.peekFile(item)
                  }
                },
                onDismissPreview = viewModel::dismissPreview,
                onRemoveFolder = { decryptLockedSafFirst ->
                  viewModel.removeFolder(folderId = route.folderId, decryptLockedSafFirst = decryptLockedSafFirst)
                },
                onPickerLaunching = viewModel::markStealthPickerInFlight,
              )
            }

            composable<CloudBackupRoute> {
              CloudBackupScreen(
                folders = state.analyticsVisibleFolders,
                backupRecords = state.backupRecords,
                driveBackups = state.driveBackups,
                isDriveSyncBusy = state.isDriveSyncBusy,
                pendingDriveRestoreItem = state.pendingDriveRestoreItem,
                securitySettings = state.securitySettings,
                devicePowerState = state.devicePowerState,
                isBatterySaverBlockingTasks = state.isBatterySaverBlockingTasks,
                pendingRestoreUriRequiringPin = state.pendingRestoreUriRequiringPin,
                onConnectGoogleDrive = startGoogleDriveAuth,
                onDisconnectGoogleDrive = viewModel::disconnectGoogleDrive,
                onRefreshGoogleDriveBackups = viewModel::refreshGoogleDriveBackups,
                onSyncGoogleDriveNow = { selectedIds ->
                  viewModel.syncVaultToGoogleDrive(
                    selectedFolderIds = selectedIds,
                    onlyAutoSync = false,
                    createTimestampedSnapshot = false,
                  )
                },
                onToggleGoogleDriveAutoSync = viewModel::setGoogleDriveAutoSyncEnabled,
                onRestoreGoogleDriveBackup = viewModel::restoreFromGoogleDrive,
                onDeleteGoogleDriveBackup = viewModel::deleteGoogleDriveBackup,
                onDismissPendingDriveRestorePin = viewModel::dismissPendingDriveRestorePin,
                onExportBackup = { uri, provider, selectedIds ->
                  viewModel.exportCloudBackup(uri, provider, selectedIds)
                },
                onRestoreBackup = { uri, pin ->
                  viewModel.restoreCloudBackup(uri, pin)
                },
                onConfigureAutoBackupTree = viewModel::configureAutoBackupDestination,
                onClearAutoBackupTree = viewModel::clearAutoBackupDestination,
                onSyncAutoBackupNow = viewModel::syncAutoBackupNow,
                onDismissPendingRestorePin = viewModel::dismissPendingRestorePinPrompt,
                modifier = topScreenModifier,
              )
            }

            composable<AuditLogRoute> {
              AuditLogScreen(
                snapshot = state.auditLogSnapshot,
                onExportReport = { uri, format, scope ->
                  viewModel.exportAuditReport(uri, format, scope)
                },
                onClearLogs = viewModel::clearAuditLogs,
                modifier = topScreenModifier,
              )
            }

            composable<SecuritySettingsRoute> {
              SecuritySettingsScreen(
                settings = state.securitySettings,
                biometricAvailability = state.biometricAvailability,
                hasAccelerometer = hasAccelerometer,
                devicePowerState = state.devicePowerState,
                isBatterySaverBlockingTasks = state.isBatterySaverBlockingTasks,
                orphanCount = state.orphanCandidates.size,
                orphanBytes = state.totalOrphanBytes,
                isScanningOrphans = state.isScanningOrphans,
                isScanningIntegrity = state.isScanningIntegrity,
                lastIntegrityCorruptedCount = state.integrityDiagnosticReport?.corruptedFiles?.size,
                dualKeyStatus = state.dualKeyStatus,
                pendingIssuesCount = state.pendingIssuesCount,
                onToggleBiometric = viewModel::setBiometricEnabled,
                onSelectPrimaryLockType = viewModel::setPrimaryLockType,
                onToggleAutoLockEnabled = viewModel::setAutoLockEnabled,
                onSelectAutoLockSeconds = viewModel::setAutoLockTimeout,
                onToggleAutoLockReencrypt = viewModel::setAutoLockReencryptFolders,
                onToggleRequireBioAfterAutoLock = viewModel::setRequireBiometricAfterAutoLock,
                onToggleIdleTimeoutEnabled = viewModel::setIdleTimeoutEnabled,
                onSelectIdleTimeoutSeconds = viewModel::setIdleTimeoutSeconds,
                onTogglePanicMode = viewModel::setPanicModeEnabled,
                onSelectPanicSensitivity = viewModel::setPanicSensitivity,
                onTogglePanicExitToHome = viewModel::setPanicExitToDeviceHome,
                onTogglePanicReencrypt = viewModel::setPanicReencryptFolders,
                onTestPanicLock = { viewModel.triggerPanicLock(source = "SETTINGS_TEST") },
                onToggleBatterySaverMode = viewModel::setBatterySaverModeEnabled,
                onAddQuickTileLock = {
                  requestAddQuickTile(
                    context = context,
                    serviceClass = LockVaultTileService::class.java,
                    labelRes = R.string.qs_tile_lock_vault,
                    iconRes = R.drawable.ic_qs_lock_vault,
                  ) { feedback ->
                    coroutineScope.launch { snackbarHostState.showSnackbar(feedback) }
                  }
                },
                onAddQuickTilePanic = {
                  requestAddQuickTile(
                    context = context,
                    serviceClass = PanicModeTileService::class.java,
                    labelRes = R.string.qs_tile_panic_mode,
                    iconRes = R.drawable.ic_qs_panic_mode,
                  ) { feedback ->
                    coroutineScope.launch { snackbarHostState.showSnackbar(feedback) }
                  }
                },
                onToggleObfuscateDefault = viewModel::setObfuscateFilenamesDefault,
                onSelectThemeMode = viewModel::setThemeMode,
                onToggleDynamicColor = viewModel::setDynamicColor,
                onChangePin = viewModel::changePin,
                onSavePattern = { nodes, done ->
                  viewModel.setOrUpdateUnlockPattern(nodes, makeDefaultFallback = true, onDone = done)
                },
                onRemovePattern = viewModel::removeUnlockPattern,
                onTogglePreferPatternFallback = viewModel::setPreferPatternFallback,
                onTogglePatternStealthMode = viewModel::setPatternStealthMode,
                onUpdateRecoveryQuestions = { q1, a1, q2, a2, customPrompts, shareSuggestions, done ->
                  viewModel.updateSecurityQuestions(q1, a1, q2, a2, customPrompts, shareSuggestions, done)
                },
                onLinkExternalDualKeyFolder = viewModel::linkExternalDualKeyFolder,
                onVerifyDualKeyVault = viewModel::verifyDualKeyVaultNow,
                onOpenDiagnostics = { navController.navigate(DiagnosticsSupportRoute) },
                onOpenOrphanCleanup = {
                  viewModel.scanOrphanedFiles()
                  showOrphanCleanupDialog = true
                },
                onOpenIntegrityDiagnostic = {
                  showIntegrityDiagnosticDialog = true
                  if (!state.isScanningIntegrity && state.integrityDiagnosticReport == null) {
                    viewModel.runIntegrityDiagnosticScan()
                  }
                },
                onOpenMigrateOut = { showMigrationWizard = true },
                onSelectStealthGestureMode = viewModel::setStealthGestureMode,
                onToggleAlwaysBlockScreenshots = viewModel::setAlwaysBlockScreenshots,
                onChangeStealthCode = viewModel::setStealthAccessCode,
                onOpenStealthVault = { viewModel.requestRevealStealthVault("SETTINGS") },
                modifier = topScreenModifier,
              )
            }

            composable<DiagnosticsSupportRoute> {
              DiagnosticsSupportScreen(
                state = state,
                onBack = {
                  if (navController.currentBackStackEntry?.destination?.hasRoute<DiagnosticsSupportRoute>() == true) {
                    navController.popBackStack()
                  }
                },
                onSignInWithGoogle = { viewModel.signInFirebaseWithGoogle(activity) },
                onSignOut = viewModel::signOutFirebase,
                onSendReport = viewModel::sendDiagnosticReport,
                onClearSentHistory = viewModel::clearSentDiagnosticHistory,
                modifier = topScreenModifier,
              )
            }
          }
        }
      }
    }

    pendingSecondaryAuthAction?.let { pending ->
      SecondaryAuthChallengeDialog(
        title = pending.title,
        description = pending.description,
        hasPatternLock = state.securitySettings.hasPatternLock,
        hasPinCredential = state.securitySettings.hasPinCredential,
        expectedPinLength = state.securitySettings.pinLength,
        preferPatternDefault = state.securitySettings.preferPatternFallback ||
          !state.securitySettings.hasPinCredential,
        stealthMode = state.securitySettings.patternStealthMode,
        onDismiss = {
          val dismissCallback = pending.onDismiss
          pendingSecondaryAuthAction = null
          dismissCallback()
        },
        onVerifyPattern = { nodes, onChecked ->
          viewModel.verifyPatternForAction(nodes, actionLabel = pending.actionLabel) { ok ->
            if (ok) {
              val authorizedAction = pending.onAuthorized
              pendingSecondaryAuthAction = null
              authorizedAction()
            }
            onChecked(ok)
          }
        },
        onVerifyPin = { pin, onChecked ->
          viewModel.verifyMasterPinForAction(pin, actionLabel = pending.actionLabel) { ok ->
            if (ok) {
              val authorizedAction = pending.onAuthorized
              pendingSecondaryAuthAction = null
              authorizedAction()
            }
            onChecked(ok)
          }
        },
      )
    }

    if (showOrphanCleanupDialog) {
      OrphanCleanupDialog(
        candidates = state.orphanCandidates,
        selectedIds = state.selectedOrphanIds,
        isScanning = state.isScanningOrphans,
        isCleaning = state.isCleaningOrphans,
        onRescan = { viewModel.scanOrphanedFiles() },
        onToggleSelect = viewModel::toggleOrphanSelection,
        onSelectAll = viewModel::selectAllOrphans,
        onConfirmDelete = viewModel::deleteSelectedOrphans,
        onDismiss = { showOrphanCleanupDialog = false },
      )
    }

    if (showIntegrityDiagnosticDialog) {
      IntegrityDiagnosticDialog(
        isScanning = state.isScanningIntegrity,
        progress = state.integrityScanProgress,
        report = state.integrityDiagnosticReport,
        onRescan = { viewModel.runIntegrityDiagnosticScan() },
        onDismiss = {
          showIntegrityDiagnosticDialog = false
          if (state.isScanningIntegrity) {
            viewModel.cancelIntegrityDiagnosticScan()
          }
        },
      )
    }

    state.selectiveLockPrompt?.let { prompt ->
      SelectiveLockFilesDialog(
        prompt = prompt,
        onConfirm = { selectedIds ->
          viewModel.lockFolder(
            folderId = prompt.folder.id,
            reloadDetail = prompt.reloadDetailAfterLock,
            selectedFileIds = selectedIds,
          )
        },
        onDismiss = viewModel::dismissSelectiveLockPrompt,
      )
    }

    if (showMigrationWizard) {
      MigrationWizardDialog(
        folders = state.analyticsVisibleFolders,
        onDismiss = { showMigrationWizard = false },
        onConfirmExport = { folderIds, format, removeOriginals ->
          showMigrationWizard = false
          val req = PendingMigrationRequest(
            folderIds = folderIds,
            format = format,
            removeOriginalsAfterSuccess = removeOriginals,
          )
          authorizeActionBiometric(
            null,
            true,
            "Authorize Exit Migration",
            "Verify with fingerprint or facial recognition to decrypt and export vault folders",
            {
              pendingSecondaryAuthAction = PendingSecondaryAuthAction(
                title = "Authorize Exit Migration",
                description = "Draw your 3×3 Pattern or enter your Master Vault PIN to decrypt and export BeastLock content out of the vault.",
                actionLabel = "Exit Migration",
                onAuthorized = { launchDestinationPicker(req) },
              )
            },
            { launchDestinationPicker(req) },
          )
        },
      )
    }

    state.migrationProgress?.let { progress ->
      MigrationProgressDialog(
        progress = progress,
        onCancel = viewModel::cancelMigrationExport,
      )
    }

    state.migrationResult?.let { result ->
      MigrationResultDialog(
        result = result,
        onDismiss = viewModel::dismissMigrationResult,
      )
    }

    if (state.isStealthRevealed && !isViewingStealthFolder) {
      StealthVaultDrawerSheet(
        stealthFolders = state.stealthFolders,
        onOpenFolder = { folderId ->
          navController.navigate(FolderDetailRoute(folderId))
        },
        onCreateStealthFolder = viewModel::createPrivateFolder,
        onRemoveFolder = { folderId ->
          viewModel.removeFolder(folderId, decryptLockedSafFirst = true)
        },
        onConcealVault = {
          viewModel.concealPrivateVault(source = "DRAWER_DISMISSED")
        },
      )
    }
  }
}

private fun requestAddQuickTile(
  context: Context,
  serviceClass: Class<*>,
  labelRes: Int,
  iconRes: Int,
  onFeedback: (String) -> Unit,
) {
  val label = context.getString(labelRes)
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    val statusBarManager = context.getSystemService(StatusBarManager::class.java)
    if (statusBarManager == null) {
      onFeedback("Status Bar service unavailable. Add '$label' manually from Quick Settings edit menu.")
      return
    }
    runCatching {
      statusBarManager.requestAddTileService(
        ComponentName(context, serviceClass),
        label,
        AndroidIcon.createWithResource(context, iconRes),
        context.mainExecutor,
      ) { resultCode ->
        val msg = when (resultCode) {
          StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
            "'$label' added to your Quick Settings panel."
          StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
            "'$label' is already pinned to your Quick Settings panel."
          StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
            "Tile not added. You can pin '$label' anytime from the Quick Settings edit menu."
          else -> "Quick Settings tile request processed."
        }
        onFeedback(msg)
      }
    }.onFailure {
      onFeedback("Swipe down Android Quick Settings, tap Edit, and drag '$label' into active tiles.")
    }
  } else {
    onFeedback("Swipe down Android Quick Settings, tap Edit (pencil), and drag '$label' into active tiles.")
  }
}

