package com.omni.sync.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AndroidUpdateInfo(
    val versionCode: Long = 0,
    val versionName: String = "development",
    val sha256: String = "",
    val size: Long = 0,
    val builtAtUtc: String = "",
    val downloadPath: String = "/api/android-update/apk"
)

enum class AndroidUpdatePhase {
    IDLE,
    CHECKING,
    CURRENT,
    AVAILABLE,
    PERMISSION_REQUIRED,
    DOWNLOADING,
    INSTALLING,
    ERROR
}

data class AndroidUpdateState(
    val phase: AndroidUpdatePhase = AndroidUpdatePhase.IDLE,
    val message: String = "Check the hub for a new Android build.",
    val progressPercent: Int? = null,
    val update: AndroidUpdateInfo? = null
)

class AndroidUpdateManager(private val context: Context) {
    private data class PendingDeployment(
        val requestId: String,
        val baseUrl: String,
        val apiKey: String,
        val info: AndroidUpdateInfo,
        val onStatus: (String, String) -> Unit
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val _state = MutableStateFlow(AndroidUpdateState())
    val state: StateFlow<AndroidUpdateState> = _state.asStateFlow()
    @Volatile
    private var pendingDeployment: PendingDeployment? = null

    fun deployLatest(
        requestId: String,
        hubUrl: String,
        apiKey: String,
        onStatus: (String, String) -> Unit
    ) {
        if (_state.value.phase == AndroidUpdatePhase.CHECKING ||
            _state.value.phase == AndroidUpdatePhase.DOWNLOADING ||
            _state.value.phase == AndroidUpdatePhase.INSTALLING
        ) {
            onStatus("busy", "Android updater is already processing another request.")
            return
        }

        scope.launch {
            val baseUrl = hubUrl.substringBefore("/signalrhub").trimEnd('/')
            Log.i(TAG, "[$requestId] Native deployment started; base URL=$baseUrl")
            onStatus("checking", "Checking the active Hub for the published APK.")
            _state.value = AndroidUpdateState(
                phase = AndroidUpdatePhase.CHECKING,
                message = "Hub requested an Android deployment..."
            )
            try {
                val info = requestMetadata(baseUrl, apiKey)
                val installedSha256 = sha256(File(context.applicationInfo.sourceDir))
                val installedVersionCode = packageVersionCode(
                    context.packageManager.getPackageInfo(context.packageName, 0)
                )
                Log.i(
                    TAG,
                    "[$requestId] Metadata received: version=${info.versionCode}, " +
                        "size=${info.size}, hash=${info.sha256.take(12)}...; " +
                        "installedVersion=$installedVersionCode, " +
                        "installedHash=${installedSha256.take(12)}..."
                )

                when {
                    info.versionCode > 0 && info.versionCode < installedVersionCode -> {
                        _state.value = AndroidUpdateState(
                            phase = AndroidUpdatePhase.CURRENT,
                            message = "The hub build is older than the installed app."
                        )
                        onStatus("current", "The published APK is older than the installed app.")
                    }
                    info.sha256.equals(installedSha256, ignoreCase = true) -> {
                        _state.value = AndroidUpdateState(
                            phase = AndroidUpdatePhase.CURRENT,
                            message = "This device already has the latest hub build."
                        )
                        onStatus("current", "The device already has the published APK.")
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !context.packageManager.canRequestPackageInstalls() -> {
                        pendingDeployment = PendingDeployment(
                            requestId,
                            baseUrl,
                            apiKey,
                            info,
                            onStatus
                        )
                        _state.value = AndroidUpdateState(
                            phase = AndroidUpdatePhase.PERMISSION_REQUIRED,
                            message = "Allow OmniSync to install updates; deployment will resume automatically.",
                            update = info
                        )
                        Log.w(TAG, "[$requestId] Install permission is required; opening settings.")
                        onStatus(
                            "permission_required",
                            "Android opened the install-unknown-apps permission screen."
                        )
                        openInstallPermission()
                    }
                    else -> installDeployment(
                        PendingDeployment(requestId, baseUrl, apiKey, info, onStatus)
                    )
                }
            } catch (error: Exception) {
                val message = error.message ?: "The Android deployment failed."
                Log.e(TAG, "[$requestId] Native deployment failed: $message", error)
                _state.value = AndroidUpdateState(
                    phase = AndroidUpdatePhase.ERROR,
                    message = message
                )
                onStatus("error", message)
            }
        }
    }

    fun continuePendingDeploymentIfPermitted() {
        val deployment = pendingDeployment ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            Log.d(TAG, "[${deployment.requestId}] Still waiting for install permission.")
            return
        }

        pendingDeployment = null
        Log.i(TAG, "[${deployment.requestId}] Install permission granted; resuming deployment.")
        deployment.onStatus("permission_granted", "Install permission granted; resuming deployment.")
        scope.launch {
            try {
                installDeployment(deployment)
            } catch (error: Exception) {
                val message = error.message ?: "The Android deployment failed."
                Log.e(TAG, "[${deployment.requestId}] Resumed deployment failed: $message", error)
                _state.value = AndroidUpdateState(
                    phase = AndroidUpdatePhase.ERROR,
                    message = message,
                    update = deployment.info
                )
                deployment.onStatus("error", message)
            }
        }
    }

    private suspend fun installDeployment(deployment: PendingDeployment) {
        Log.i(TAG, "[${deployment.requestId}] Downloading APK from ${deployment.baseUrl}.")
        deployment.onStatus("downloading", "Downloading the APK from the active Hub connection.")
        val apk = downloadApk(deployment.baseUrl, deployment.apiKey, deployment.info)
        Log.i(TAG, "[${deployment.requestId}] Download complete; validating APK.")
        validateApk(apk, deployment.info)
        deployment.onStatus("validated", "APK hash, package, version, and signer are valid.")
        Log.i(TAG, "[${deployment.requestId}] APK validated; committing PackageInstaller session.")
        commitInstall(apk, deployment.requestId)
        deployment.onStatus("installer_started", "Android PackageInstaller accepted the APK.")
    }

    fun checkForUpdate(hubUrl: String, apiKey: String) {
        if (_state.value.phase == AndroidUpdatePhase.CHECKING ||
            _state.value.phase == AndroidUpdatePhase.DOWNLOADING ||
            _state.value.phase == AndroidUpdatePhase.INSTALLING
        ) return

        scope.launch {
            _state.value = AndroidUpdateState(
                phase = AndroidUpdatePhase.CHECKING,
                message = "Checking the hub for a new build..."
            )
            try {
                val baseUrl = hubUrl.substringBefore("/signalrhub").trimEnd('/')
                val info = requestMetadata(baseUrl, apiKey)
                val installedSha256 = sha256(File(context.applicationInfo.sourceDir))
                val installedVersionCode = packageVersionCode(
                    context.packageManager.getPackageInfo(context.packageName, 0)
                )
                _state.value = if (info.versionCode > 0 && info.versionCode < installedVersionCode) {
                    AndroidUpdateState(
                        phase = AndroidUpdatePhase.CURRENT,
                        message = "The hub build is older than the installed app."
                    )
                } else if (info.sha256.equals(installedSha256, ignoreCase = true)) {
                    AndroidUpdateState(
                        phase = AndroidUpdatePhase.CURRENT,
                        message = "This device already has the latest hub build."
                    )
                } else {
                    AndroidUpdateState(
                        phase = AndroidUpdatePhase.AVAILABLE,
                        message = "Build ${info.versionName} is ready (${formatSize(info.size)}).",
                        update = info
                    )
                }
            } catch (error: Exception) {
                _state.value = AndroidUpdateState(
                    phase = AndroidUpdatePhase.ERROR,
                    message = error.message ?: "Could not check for an Android update."
                )
            }
        }
    }

    fun installAvailableUpdate(hubUrl: String, apiKey: String) {
        val info = _state.value.update ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            _state.value = AndroidUpdateState(
                phase = AndroidUpdatePhase.PERMISSION_REQUIRED,
                message = "Allow OmniSync to install updates, then tap Continue install.",
                update = info
            )
            openInstallPermission()
            return
        }

        scope.launch {
            try {
                val baseUrl = hubUrl.substringBefore("/signalrhub").trimEnd('/')
                val apk = downloadApk(baseUrl, apiKey, info)
                validateApk(apk, info)
                commitInstall(apk)
            } catch (error: Exception) {
                _state.value = AndroidUpdateState(
                    phase = AndroidUpdatePhase.ERROR,
                    message = error.message ?: "The Android update failed.",
                    update = info
                )
            }
        }
    }

    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun handleInstallStatus(status: Int, message: String?) {
        _state.value = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> AndroidUpdateState(
                phase = AndroidUpdatePhase.INSTALLING,
                message = "Approve the Android system update prompt."
            )
            PackageInstaller.STATUS_SUCCESS -> AndroidUpdateState(
                phase = AndroidUpdatePhase.CURRENT,
                message = "Android update installed."
            )
            else -> AndroidUpdateState(
                phase = AndroidUpdatePhase.ERROR,
                message = message ?: "Android rejected the update (status $status)."
            )
        }
    }

    private fun requestMetadata(baseUrl: String, apiKey: String): AndroidUpdateInfo {
        Log.d(TAG, "Requesting Android update metadata from $baseUrl.")
        val connection = openConnection("$baseUrl/api/android-update", apiKey)
        try {
            ensureSuccess(connection, "check for updates")
            return connection.inputStream.bufferedReader().use { reader ->
                gson.fromJson(reader, AndroidUpdateInfo::class.java)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadApk(
        baseUrl: String,
        apiKey: String,
        info: AndroidUpdateInfo
    ): File {
        Log.d(TAG, "Downloading Android update from $baseUrl${info.downloadPath}.")
        _state.value = AndroidUpdateState(
            phase = AndroidUpdatePhase.DOWNLOADING,
            message = "Downloading Android update...",
            progressPercent = 0,
            update = info
        )
        val connection = openConnection("$baseUrl${info.downloadPath}", apiKey)
        val updateDirectory = File(context.cacheDir, "apk-updates").apply { mkdirs() }
        val temporaryApk = File(updateDirectory, "omnisync-update.apk.part")
        val finalApk = File(updateDirectory, "omnisync-update.apk")

        try {
            ensureSuccess(connection, "download the update")
            val expectedSize = connection.contentLengthLong.takeIf { it > 0 } ?: info.size
            connection.inputStream.use { input ->
                temporaryApk.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val percent = if (expectedSize > 0) {
                            ((downloaded * 100) / expectedSize).toInt().coerceIn(0, 100)
                        } else null
                        _state.value = AndroidUpdateState(
                            phase = AndroidUpdatePhase.DOWNLOADING,
                            message = "Downloading Android update...",
                            progressPercent = percent,
                            update = info
                        )
                    }
                }
            }
            if (finalApk.exists()) finalApk.delete()
            if (!temporaryApk.renameTo(finalApk)) {
                throw IllegalStateException("Could not finalize the downloaded APK.")
            }
            Log.i(TAG, "Downloaded ${finalApk.length()} bytes to ${finalApk.absolutePath}.")
            return finalApk
        } finally {
            connection.disconnect()
        }
    }

    private fun validateApk(apk: File, info: AndroidUpdateInfo) {
        if (!sha256(apk).equals(info.sha256, ignoreCase = true)) {
            apk.delete()
            throw SecurityException("The downloaded APK hash does not match the hub metadata.")
        }

        val packageManager = context.packageManager
        val signingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archiveInfo = packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            signingFlags
        ) ?: throw SecurityException("The downloaded file is not a readable APK.")
        if (archiveInfo.packageName != context.packageName) {
            throw SecurityException("The downloaded APK belongs to a different application.")
        }
        if (info.versionCode > 0 && packageVersionCode(archiveInfo) != info.versionCode) {
            throw SecurityException("The downloaded APK version does not match the hub metadata.")
        }

        val installedInfo = packageManager.getPackageInfo(
            context.packageName,
            signingFlags
        )
        if (signerDigests(archiveInfo).intersect(signerDigests(installedInfo)).isEmpty()) {
            throw SecurityException("The downloaded APK is not signed by the installed app signer.")
        }
        Log.i(TAG, "APK validation completed successfully.")
    }

    private fun commitInstall(apk: File, deploymentRequestId: String? = null) {
        _state.value = AndroidUpdateState(
            phase = AndroidUpdatePhase.INSTALLING,
            message = "Handing the update to Android..."
        )
        val installer = context.packageManager.packageInstaller
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(parameters)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callbackIntent = Intent(context, AndroidUpdateReceiver::class.java).apply {
                action = AndroidUpdateReceiver.ACTION_INSTALL_STATUS
                deploymentRequestId?.let {
                    putExtra(AndroidUpdateReceiver.EXTRA_DEPLOYMENT_REQUEST_ID, it)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(context, sessionId, callbackIntent, flags)
            Log.i(TAG, "Committing PackageInstaller session $sessionId.")
            session.commit(callback.intentSender)
        }
        apk.delete()
    }

    private fun openConnection(url: String, apiKey: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 60_000
            setRequestProperty("X-Omni-Api-Key", apiKey)
        }
    }

    private fun ensureSuccess(connection: HttpURLConnection, action: String) {
        if (connection.responseCode in 200..299) return
        val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
        throw IllegalStateException(
            "Could not $action: HTTP ${connection.responseCode}" +
                if (detail.isNullOrBlank()) "." else " - $detail"
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            packageInfo.signatures
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(packageInfo: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }

    private fun formatSize(bytes: Long): String {
        return if (bytes >= 1024 * 1024) {
            "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
        } else {
            "%.0f KB".format(bytes.toDouble() / 1024)
        }
    }

    companion object {
        private const val TAG = "AndroidUpdateManager"
    }
}
