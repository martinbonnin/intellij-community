// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.impl

import org.jetbrains.intellij.build.JvmArchitecture
import org.jetbrains.intellij.build.OsFamily
import java.nio.file.Path

interface BundledRuntime {
  fun getHomeForCurrentOsAndArch(): Path

  /**
   * contract: returns a directory, where only one subdirectory is available: 'jbr', which contains specified JBR
   */
  fun extract(prefix: String, os: OsFamily, arch: JvmArchitecture): Path

  fun extractTo(prefix: String, os: OsFamily, destinationDir: Path, arch: JvmArchitecture)

  fun archiveName(prefix: String, arch: JvmArchitecture, os: OsFamily): String

  fun findArchive(prefix: String, os: OsFamily, arch: JvmArchitecture): Path

  fun checkExecutablePermissions(distribution: Path, root: String, os: OsFamily)

  fun executableFilesPatterns(os: OsFamily): List<String>
}
