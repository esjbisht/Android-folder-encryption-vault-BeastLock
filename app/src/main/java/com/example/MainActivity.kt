package com.example

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.example.crypto.BiometricAuthHelper
import com.example.crypto.BiometricAuthResult
import com.example.data.backup.DriveAuthOutcome
import com.example.data.backup.GoogleDriveAuthHelper
import com.example.data.storage.MigrationExportFormat
import com.example.quicksettings.LockVaultTileService
import com.example.quicksettings.PanicModeTileService
import com.example.security.PanicShakeMonitor
import com.example.ui.AuditLogRoute
import com.example.ui.CloudBackupRoute
import com.example.ui.DashboardRoute
import com.example.ui.FolderDetailRoute
import com.example.ui.SecuritySettingsRoute
import com.example.ui.StorageAnalyticsRoute
import com.example.ui.VaultSessionState
import com.example.ui.VaultViewModel
import com.example.ui.components.CloudBackupProgressDialog
import com.example.ui.components.EncryptionProgressDialog
import com.example.ui.components.IntegrityDiagnosticDialog
import com.example.ui.components.MigrationProgressDialog
import com.example.ui.components.MigrationResultDialog
import com.example.ui.components.MigrationWizardDialog
import com.example.ui.components.OrphanCleanupDialog
import com.example.ui.components.SelectiveLockFilesDialog
import com.example.ui.screens.AuditLogScreen
import com.example.ui.screens.CloudBackupScreen
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
          panicMonitor.stop()
          lifecycleOwner.lifecycle.removeObserver(observer)
        }
      }

      val triggerUnlockBiometric: () -> Unit = {
        BiometricAuthHelper.authenticate(
          activity = this@MainActivity,
          title = getString(R.string.biometric_prompt_title),
          subtitle = getString(R.string.biometric_prompt_subtitle),
          negativeButtonText = getString(R.string.biometric_negative_use_pin),
          onAuthAttemptFailed = { reason ->
            viewModel.recordFailedBiometricAttempt(reason, "BeastLock Gate")
          },
        ) { result ->
          if (result is BiometricAuthResult.Success) {
            viewModel.onBiometricUnlockSuccess()
          }
        }
      }

      val authorizeActionBiometric: (fallbackToPin: () -> Unit, onAuthorized: () -> Unit) -> Unit =
        { fallbackToPin, onAuthorized ->
          if (state.securitySettings.biometricEnabled && state.biometricAvailability.canAuthenticate) {
            BiometricAuthHelper.authenticate(
              activity = this@MainActivity,
              title = getString(R.string.biometric_action_title),
              subtitle = getString(R.string.biometric_action_subtitle),
              negativeButtonText = getString(R.string.biometric_negative_use_pin),
              allowDeviceCredential = true,
              onAuthAttemptFailed = { reason ->
                viewModel.recordFailedBiometricAttempt(reason, "Vault Security Action")
              },
            ) { result ->
              when (result) {
                BiometricAuthResult.Success -> onAuthorized()
                BiometricAuthResult.FallbackToPin -> fallbackToPin()
                else -> Unit
              }
            }
          } else {
            fallbackToPin()
          }
        }

      BeastLockTheme(
        themeMode = state.securitySettings.themeMode,
        dynamicColor = state.securitySettings.dynamicColor,
      ) {
        when (state.sessionState) {
          VaultSessionState.Loading -> {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
              CircularProgressIndicator()
            }
          }
          VaultSessionState.NeedsOnboarding -> {
            OnboardingLockSetupScreen(
              biometricAvailability = state.biometricAvailability,
              themeMode = state.securitySettings.themeMode,
              onSelectThemeMode = viewModel::setThemeMode,
              onCompleteSetup = { pin, enableBio, q1, a1, q2, a2 ->
                viewModel.completeOnboarding(pin, enableBio, q1, a1, q2, a2)
              },
            )
          }
          VaultSessionState.Locked -> {
            VaultAuthGateScreen(
              settings = state.securitySettings,
              biometricAvailability = state.biometricAvailability,
              authError = state.authError,
              recoveryError = state.recoveryError,
              onPinSubmitted = viewModel::unlockWithPin,
              onPatternSubmitted = viewModel::unlockWithPattern,
              onRequestBiometricPrompt = triggerUnlockBiometric,
              onRecoverPin = viewModel::recoverPinWithSecurityQuestions,
              onSelectThemeMode = viewModel::setThemeMode,
              onClearErrors = viewModel::clearMessages,
            )
          }
          VaultSessionState.Unlocked -> {
            VaultUnlockedAppShell(
              activity = this@MainActivity,
              viewModel = viewModel,
              hasAccelerometer = panicMonitor.hasAccelerometer,
              authorizeActionBiometric = authorizeActionBiometric,
            )
          }
        }

        state.encryptionProgress?.let { EncryptionProgressDialog(it) }
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

@Composable
private fun VaultUnlockedAppShell(
  activity: FragmentActivity,
  viewModel: VaultViewModel,
  hasAccelerometer: Boolean,
  authorizeActionBiometric: (fallbackToPin: () -> Unit, onAuthorized: () -> Unit) -> Unit,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentDestination = navBackStackEntry?.destination
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var pendingReencryptIncludeUnlocked by remember { mutableStateOf<Boolean?>(null) }
  var showOrphanCleanupDialog by remember { mutableStateOf(false) }
  var showIntegrityDiagnosticDialog by remember { mutableStateOf(false) }
  var showMigrationWizard by remember { mutableStateOf(false) }
  var activeMigrationRequest by remember { mutableStateOf<PendingMigrationRequest?>(null) }
  var pendingMigrationPinRequest by remember { mutableStateOf<PendingMigrationRequest?>(null) }

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

        NavHost(
          navController = navController,
          startDestination = DashboardRoute,
          modifier = Modifier.weight(1f),
        ) {
          composable<DashboardRoute> {
            VaultDashboardScreen(
              state = state,
              onSelectFilter = viewModel::setFilter,
              onSearchQueryChange = viewModel::setSearchQuery,
              onPickDeviceFolder = viewModel::addDeviceFolder,
              onDecryptAnyDeviceFolder = viewModel::decryptAnyDeviceFolder,
              onCreatePrivateFolder = viewModel::createPrivateFolder,
              onOpenFolder = { folderId ->
                navController.navigate(FolderDetailRoute(folderId))
              },
              onToggleLockFolder = { folder ->
                if (folder.isLocked) {
                  if (folder.requireBiometricPerUnlock) {
                    authorizeActionBiometric(
                      { viewModel.unlockFolder(folder.id) },
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
                    { viewModel.decryptSelectedFolders() },
                    { viewModel.decryptSelectedFolders() },
                  )
                } else {
                  viewModel.decryptSelectedFolders()
                }
              },
              onReencryptSelectedFolders = { includeUnlocked ->
                authorizeActionBiometric(
                  { pendingReencryptIncludeUnlocked = includeUnlocked },
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
              onLockVaultNow = { viewModel.lockVaultNow() },
              onSelectThemeMode = viewModel::setThemeMode,
              modifier = Modifier.statusBarsPadding(),
            )
          }

          composable<StorageAnalyticsRoute> {
            StorageAnalyticsScreen(
              state = state,
              onOpenFolder = { folderId ->
                navController.navigate(FolderDetailRoute(folderId))
              },
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
              modifier = Modifier.statusBarsPadding(),
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
                    { viewModel.unlockFolder(route.folderId, reloadDetail = true) },
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
                    { viewModel.decryptFilesInFolder(route.folderId, fileIds, reloadDetail = true) },
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
              onPeekFile = viewModel::peekFile,
              onDismissPreview = viewModel::dismissPreview,
              onRemoveFolder = { decryptLockedSafFirst ->
                viewModel.removeFolder(route.folderId, decryptLockedSafFirst = decryptLockedSafFirst)
              },
            )
          }

          composable<CloudBackupRoute> {
            CloudBackupScreen(
              folders = state.folders,
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
              modifier = Modifier.statusBarsPadding(),
            )
          }

          composable<AuditLogRoute> {
            AuditLogScreen(
              snapshot = state.auditLogSnapshot,
              onExportReport = { uri, format, scope ->
                viewModel.exportAuditReport(uri, format, scope)
              },
              onClearLogs = viewModel::clearAuditLogs,
              modifier = Modifier.statusBarsPadding(),
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
              onToggleBiometric = viewModel::setBiometricEnabled,
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
              onUpdateRecoveryQuestions = { q1, a1, q2, a2, done ->
                viewModel.updateSecurityQuestions(q1, a1, q2, a2, done)
              },
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
              modifier = Modifier.statusBarsPadding(),
            )
          }
        }
      }
    }

    pendingReencryptIncludeUnlocked?.let { includeUnlocked ->
      ReencryptPinVerificationDialog(
        description = "Enter your Master Vault PIN to authorize key rotation and re-encryption.",
        onDismiss = { pendingReencryptIncludeUnlocked = null },
        onVerifyPin = { pin, onChecked ->
          viewModel.verifyMasterPinForReencrypt(pin) { ok ->
            if (ok) {
              pendingReencryptIncludeUnlocked = null
              viewModel.reencryptSelectedFolders(includeUnlocked)
            }
            onChecked(ok)
          }
        },
      )
    }

    pendingMigrationPinRequest?.let { req ->
      ReencryptPinVerificationDialog(
        description = "Enter your Master Vault PIN to decrypt and export BeastLock content out of the vault.",
        onDismiss = { pendingMigrationPinRequest = null },
        onVerifyPin = { pin, onChecked ->
          viewModel.verifyMasterPinForMigration(pin) { ok ->
            if (ok) {
              pendingMigrationPinRequest = null
              launchDestinationPicker(req)
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
        folders = state.folders,
        onDismiss = { showMigrationWizard = false },
        onConfirmExport = { folderIds, format, removeOriginals ->
          showMigrationWizard = false
          val req = PendingMigrationRequest(
            folderIds = folderIds,
            format = format,
            removeOriginalsAfterSuccess = removeOriginals,
          )
          authorizeActionBiometric(
            { pendingMigrationPinRequest = req },
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
  }
}

@Composable
private fun ReencryptPinVerificationDialog(
  description: String = "Enter your Master Vault PIN to authorize key rotation and re-encryption.",
  onDismiss: () -> Unit,
  onVerifyPin: (pin: String, onChecked: (Boolean) -> Unit) -> Unit,
) {
  var pin by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  var isVerifying by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = { if (!isVerifying) onDismiss() },
    title = { Text("Verify Master PIN") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = pin,
          onValueChange = {
            pin = it.filter { ch -> ch.isDigit() }.take(12)
            error = null
          },
          label = { Text("Master PIN") },
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          singleLine = true,
          isError = error != null,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("reencrypt_pin_input"),
        )
        if (error != null) {
          Text(
            text = error!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (pin.length < 4) {
            error = "Enter your Master PIN"
          } else {
            isVerifying = true
            onVerifyPin(pin) { ok ->
              isVerifying = false
              if (!ok) {
                error = "Incorrect Master PIN"
              }
            }
          }
        },
        enabled = !isVerifying,
        modifier = Modifier.testTag("reencrypt_pin_confirm_button"),
      ) {
        Text(if (isVerifying) "Verifying..." else "Authorize")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        enabled = !isVerifying,
      ) {
        Text("Cancel")
      }
    },
    modifier = Modifier.testTag("reencrypt_pin_dialog"),
  )
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

