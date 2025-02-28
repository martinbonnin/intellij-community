package com.intellij.settingsSync

import com.intellij.ide.plugins.PluginManagerCore.isRunningFromSources
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.io.FileUtil
import com.intellij.settingsSync.auth.SettingsSyncAuthService
import com.intellij.util.io.delete
import com.intellij.util.io.inputStream
import com.jetbrains.cloudconfig.*
import com.jetbrains.cloudconfig.auth.JbaTokenAuthProvider
import com.jetbrains.cloudconfig.exception.InvalidVersionIdException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val SETTINGS_SYNC_SNAPSHOT = "settings.sync.snapshot"
internal const val SETTINGS_SYNC_SNAPSHOT_ZIP = "$SETTINGS_SYNC_SNAPSHOT.zip"

private const val DEFAULT_PRODUCTION_URL = "https://cloudconfig.jetbrains.com/cloudconfig"
private const val DEFAULT_DEBUG_URL = "https://stgn.cloudconfig.jetbrains.com/cloudconfig"
private const val URL_PROPERTY = "idea.settings.sync.cloud.url"

private const val TIMEOUT = 10000

internal class CloudConfigServerCommunicator : SettingsSyncRemoteCommunicator {

  private val client get() = _client.value

  private val _client = lazy {
    val conf = createConfiguration()
    CloudConfigFileClientV2(url, conf, DUMMY_ETAG_STORAGE, clientVersionContext)
  }

  internal val url get() = _url.value

  private val _url = lazy {
    val explicitUrl = System.getProperty(URL_PROPERTY)
    when {
      explicitUrl != null -> {
        LOG.info("Using URL from properties: $explicitUrl")
        explicitUrl
      }
      isRunningFromSources() -> DEFAULT_DEBUG_URL
      else -> DEFAULT_PRODUCTION_URL
    }
  }

  private val currentVersionOfFiles = mutableMapOf<String, String>() // todo persist this information
  private val clientVersionContext = VersionContext()

  private fun createConfiguration(): Configuration {
    val userId = SettingsSyncAuthService.getInstance().getUserData()?.id
    if (userId == null) {
      throw SettingsSyncAuthException("Authentication required")
    }
    return Configuration().connectTimeout(TIMEOUT).readTimeout(TIMEOUT).auth(JbaTokenAuthProvider(userId))
  }

  private fun receiveSnapshotFile(): InputStream? {
    return clientVersionContext.doWithVersion(null) {
      client.read(SETTINGS_SYNC_SNAPSHOT_ZIP)
    }
  }

  private fun sendSnapshotFile(inputStream: InputStream) {
    val currentVersion = getCurrentVersion()
    LOG.info("Sending $SETTINGS_SYNC_SNAPSHOT_ZIP, current version: $currentVersion")
    clientVersionContext.doWithVersion(currentVersion) {
      client.write(SETTINGS_SYNC_SNAPSHOT_ZIP, inputStream)
    }
  }

  private fun getCurrentVersion(): String? {
    val rememberedVersion = currentVersionOfFiles[SETTINGS_SYNC_SNAPSHOT_ZIP]
    if (rememberedVersion != null) {
      return rememberedVersion
    }

    // todo in this case we should update first and not just overwrite
    val serverVersion = try {
      client.getLatestVersion(SETTINGS_SYNC_SNAPSHOT_ZIP)?.versionId
    }
    catch (e: FileNotFoundException) {
      LOG.info("File not found on server")
      null
    }

    return if (serverVersion != null) {
      LOG.warn("Current version is null, using the version from the server: $serverVersion")
      serverVersion
    }
    else {
      LOG.info("No settings file on the server")
      null
    }

  }

  override fun checkServerState(): ServerState {
    try {
      when (client.getLatestVersion(SETTINGS_SYNC_SNAPSHOT_ZIP)?.versionId) {
        null -> return ServerState.FileNotExists
        currentVersionOfFiles[SETTINGS_SYNC_SNAPSHOT_ZIP] -> return ServerState.UpToDate
        else -> return ServerState.UpdateNeeded
      }
    }
    catch (e: Throwable) {
      val message = handleRemoteError(e)
      return ServerState.Error(message)
    }
  }

  override fun receiveUpdates(): UpdateResult {
    LOG.info("Receiving settings snapshot from the cloud config server...")
    try {
      val stream = receiveSnapshotFile()
      if (stream == null) {
        LOG.info("$SETTINGS_SYNC_SNAPSHOT_ZIP not found on the server")
        return UpdateResult.NoFileOnServer
      }

      val tempFile = FileUtil.createTempFile(SETTINGS_SYNC_SNAPSHOT, UUID.randomUUID().toString() + ".zip")
      try {
        FileUtil.writeToFile(tempFile, stream.readAllBytes())
        val snapshot = SettingsSnapshotZipSerializer.extractFromZip(tempFile.toPath())
        return if (snapshot.isEmpty()) UpdateResult.NoFileOnServer else UpdateResult.Success(snapshot)
      }
      finally {
        FileUtil.delete(tempFile)
      }
    }
    catch (e: Throwable) {
      val message = handleRemoteError(e)
      return UpdateResult.Error(message)
    }
  }

  override fun push(snapshot: SettingsSnapshot): SettingsSyncPushResult {
    LOG.info("Pushing setting snapshot to the cloud config server...")
    val zip = try {
      SettingsSnapshotZipSerializer.serializeToZip(snapshot)
    }
    catch (e: Throwable) {
      LOG.warn(e)
      return SettingsSyncPushResult.Error(e.message ?: "Couldn't prepare zip file")
    }

    try {
      sendSnapshotFile(zip.inputStream())
      return SettingsSyncPushResult.Success
    }
    catch (ive: InvalidVersionIdException) {
      LOG.info("Rejected: version doesn't match the version on server: ${ive.message}")
      return SettingsSyncPushResult.Rejected
    }
    // todo handle authentication failure: propose to login
    catch (e: Throwable) {
      val message = handleRemoteError(e)
      return SettingsSyncPushResult.Error(message)
    }
    finally {
      try {
        zip.delete()
      }
      catch (e: Throwable) {
        LOG.warn(e)
      }
    }
  }

  private fun handleRemoteError(e: Throwable): String {
    val defaultMessage = "Error during communication with server"
    if (e is IOException) {
      LOG.warn(e)
      return e.message ?: defaultMessage
    }
    else {
      LOG.error(e)
      return defaultMessage
    }
  }

  fun downloadSnapshot(version: FileVersionInfo): InputStream? {
    val stream = clientVersionContext.doWithVersion(version.versionId) {
      client.read(SETTINGS_SYNC_SNAPSHOT_ZIP)
    }

    if (stream == null) {
      LOG.info("$SETTINGS_SYNC_SNAPSHOT_ZIP not found on the server")
    }

    return stream
  }

  fun getLatestVersion(): FileVersionInfo? {
    return client.getLatestVersion(SETTINGS_SYNC_SNAPSHOT_ZIP)
  }

  @Throws(IOException::class)
  override fun delete() {
    currentVersionOfFiles.remove(SETTINGS_SYNC_SNAPSHOT_ZIP)
    client.delete(SETTINGS_SYNC_SNAPSHOT_ZIP)
  }

  @Throws(Exception::class)
  fun fetchHistory(): List<FileVersionInfo> {
    return client.getVersions(SETTINGS_SYNC_SNAPSHOT_ZIP)
  }

  private inner class VersionContext : HeaderStorage {
    private val contextVersionMap = mutableMapOf<String, String>()
    private val lock = ReentrantLock()

    override fun get(path: String): String? {
      return contextVersionMap[path]
    }

    override fun store(path: String, value: String) {
      contextVersionMap[path] = value
    }

    override fun remove(path: String?) {
      contextVersionMap.remove(path)
    }

    fun <T> doWithVersion(version: String?, function: () -> T): T {
      val path = SETTINGS_SYNC_SNAPSHOT_ZIP
      return lock.withLock {
        try {
          if (version != null) {
            contextVersionMap[path] = version
          }

          val result = function()

          val actualVersion: String? = contextVersionMap[path]
          if (actualVersion == null) {
            LOG.warn("Version not stored in the context for $path")
          }
          else {
            currentVersionOfFiles[path] = actualVersion
          }
          result
        }
        finally {
          contextVersionMap.clear()
        }
      }
    }
  }

  companion object {
    private val LOG = logger<CloudConfigServerCommunicator>()

    private val DUMMY_ETAG_STORAGE: ETagStorage = object : ETagStorage {
      override fun get(path: String): String? {
        return null
      }

      override fun store(path: String, value: String) {
        // do nothing
      }

      override fun remove(path: String?) {
        // do nothing
      }
    }
  }
}
