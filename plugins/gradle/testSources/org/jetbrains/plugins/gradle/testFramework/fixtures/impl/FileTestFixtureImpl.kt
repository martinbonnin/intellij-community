// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.testFramework.fixtures.impl

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.externalSystem.autoimport.changes.vfs.VirtualFileChangesListener
import com.intellij.openapi.externalSystem.autoimport.changes.vfs.VirtualFileChangesListener.Companion.installBulkVirtualFileListener
import com.intellij.openapi.externalSystem.util.*
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.runAll
import com.intellij.util.throwIfNotEmpty
import com.intellij.util.xmlb.XmlSerializer
import org.jetbrains.plugins.gradle.testFramework.fixtures.FileTestFixture
import org.jetbrains.plugins.gradle.testFramework.util.onFailureCatching
import org.jetbrains.plugins.gradle.testFramework.util.withSuppressedErrors
import java.nio.file.Path
import java.util.*

internal class FileTestFixtureImpl(
  private val relativePath: String,
  private val configure: FileTestFixture.Builder.() -> Unit
) : FileTestFixture {

  private var isInitialized: Boolean = false
  private var isSuppressedErrors: Boolean = false
  private lateinit var errors: MutableList<Throwable>
  private lateinit var snapshots: MutableMap<Path, Optional<String>>

  private lateinit var testRootDisposable: Disposable
  private lateinit var fixtureRoot: VirtualFile
  private lateinit var fixtureStateFile: VirtualFile

  override val root: VirtualFile get() = fixtureRoot

  override fun setUp() {
    isInitialized = false
    isSuppressedErrors = false
    errors = ArrayList()
    snapshots = HashMap()

    testRootDisposable = Disposer.newDisposable()

    fixtureRoot = createFixtureRoot(relativePath)
    fixtureStateFile = createFixtureStateFile()

    installFixtureFilesWatcher()

    withSuppressedErrors {
      repairFixtureCaches()
      dumpFixtureState()
    }

    withSuppressedErrors {
      runCatching { configureFixtureCaches() }
        .onFailureCatching { invalidateFixtureCaches() }
        .getOrThrow()
      root.refreshAndWait()
    }

    isInitialized = true
    dumpFixtureState()
  }

  override fun tearDown() {
    runAll(
      { rollbackAll() },
      { throwIfNotEmpty(getErrors()) },
      { Disposer.dispose(testRootDisposable) }
    )
  }

  private fun createFixtureRoot(relativePath: String): VirtualFile {
    val fileSystem = LocalFileSystem.getInstance()
    val systemPath = Path.of(PathManager.getSystemPath()).getAbsoluteNioPath("..")
    val systemDirectory = fileSystem.findOrCreateDirectory(systemPath)
    val fixtureRoot = "testData/$relativePath"
    VfsRootAccess.allowRootAccess(testRootDisposable, systemDirectory.path + "/$fixtureRoot")
    return runWriteActionAndGet {
      systemDirectory.findOrCreateDirectory(fixtureRoot)
    }
  }

  private fun createFixtureStateFile(): VirtualFile {
    return runWriteActionAndGet {
      root.findOrCreateFile("_FileTestFixture.xml")
    }
  }

  private fun repairFixtureCaches() {
    val state = readFixtureState()
    val isInitialized = state.isInitialized ?: false
    val isSuppressedErrors = state.isSuppressedErrors ?: false
    val errors = state.errors ?: emptyList()
    val snapshots = state.snapshots ?: emptyMap()
    if (!isInitialized || isSuppressedErrors || errors.isNotEmpty()) {
      invalidateFixtureCaches()
    }
    else {
      for ((path, text) in snapshots) {
        revertFile(path, text)
      }
    }
  }

  private fun invalidateFixtureCaches() {
    runWriteActionAndWait {
      root.deleteChildren { it != fixtureStateFile }
    }
  }

  private fun dumpFixtureState() {
    val errors = errors.map { it.message ?: it.toString() }
    val snapshots = snapshots.entries.associate { (k, v) -> root.getRelativePath(k) to v.orElse(null) }
    writeFixtureState(State(isInitialized, isSuppressedErrors, errors, snapshots))
  }

  private fun readFixtureState(): State {
    return runCatching {
      runReadAction {
        val element = JDOMUtil.load(fixtureStateFile.toNioPath())
        XmlSerializer.deserialize(element, State::class.java)
      }
    }.getOrElse { State() }
  }

  private fun writeFixtureState(state: State) {
    runWriteActionAndWait {
      val element = XmlSerializer.serialize(state)
      JDOMUtil.write(element, fixtureStateFile.toNioPath())
    }
  }

  private fun configureFixtureCaches() {
    val builder = Builder()
    builder.configure()
    if (!areContentsEqual(builder.files)) {
      invalidateFixtureCaches()
      createFiles(builder.files)
      builder.builders.forEach { it(root) }
    }
  }

  private fun areContentsEqual(files: Map<String, String>): Boolean {
    for ((path, expectedContent) in files) {
      val content = loadText(path)
      if (expectedContent != content) {
        return false
      }
    }
    return true
  }

  private fun createFiles(files: Map<String, String>) {
    runWriteActionAndWait {
      for ((path, content) in files) {
        val file = root.createFile(path)
        file.text = content
      }
    }
  }

  override fun isModified(): Boolean {
    return snapshots.isNotEmpty()
  }

  override fun hasErrors(): Boolean {
    return getErrors().isNotEmpty()
  }

  private fun getErrors(): List<Throwable> {
    root.refreshAndWait()
    return errors
  }

  override fun snapshot(relativePath: String) {
    snapshot(root.getAbsoluteNioPath(relativePath))
  }

  private fun snapshot(path: Path) {
    if (path in snapshots) return
    val text = loadText(path)
    snapshots[path] = Optional.ofNullable(text)
    dumpFixtureState()
  }

  override fun rollbackAll() {
    for (path in snapshots.keys.toSet()) {
      rollback(path)
    }
  }

  override fun rollback(relativePath: String) {
    rollback(root.getAbsoluteNioPath(relativePath))
  }

  private fun rollback(path: Path) {
    val text = requireNotNull(snapshots[path]) { "Cannot find snapshot for $path" }
    revertFile(path, text.orElse(null))
    snapshots.remove(path)
    dumpFixtureState()
  }

  private fun revertFile(relativePath: String, text: String?) {
    revertFile(root.getAbsoluteNioPath(relativePath), text)
  }

  private fun revertFile(path: Path, text: String?) {
    runWriteActionAndWait {
      if (text != null) {
        root.fileSystem.findOrCreateFile(path)
          .also { it.text = text }
      }
      else {
        root.fileSystem.deleteFileOrDirectory(path)
      }
    }
  }

  private fun loadText(relativePath: String): String? {
    return loadText(root.getAbsoluteNioPath(relativePath))
  }

  private fun loadText(path: Path): String? {
    return runReadAction {
      root.fileSystem.findFile(path)?.text
    }
  }

  override fun suppressErrors(isSuppressedErrors: Boolean) {
    this.isSuppressedErrors = isSuppressedErrors
    dumpFixtureState()
  }

  override fun addIllegalOperationError(message: String) {
    if (!isSuppressedErrors) {
      errors.add(Exception(message))
      dumpFixtureState()
    }
  }

  private fun installFixtureFilesWatcher() {
    val listener = object : VirtualFileChangesListener {
      override fun isProcessRecursively(): Boolean = true

      override fun isRelevant(file: VirtualFile, event: VFileEvent): Boolean {
        return !file.isDirectory &&
               file != fixtureStateFile &&
               VfsUtil.isAncestor(root, file, false) &&
               file.toNioPath() !in snapshots
      }

      override fun updateFile(file: VirtualFile, event: VFileEvent) {
        addIllegalOperationError("Unexpected project modification $event")
      }
    }
    installBulkVirtualFileListener(listener, testRootDisposable)
  }

  private class Builder : FileTestFixture.Builder {

    val files = HashMap<String, String>()
    val builders = ArrayList<(VirtualFile) -> Unit>()

    override fun withFile(relativePath: String, content: String) {
      files[relativePath] = content
    }

    override fun withFiles(action: (VirtualFile) -> Unit) {
      builders.add(action)
    }
  }

  private data class State(
    var isInitialized: Boolean? = null,
    var isSuppressedErrors: Boolean? = null,
    var errors: List<String>? = null,
    var snapshots: Map<String, String?>? = null
  )
}