package com.intellij.ide.starter.models

import com.intellij.ide.starter.system.SystemInfo

data class IdeInfo(
  val productCode: String,
  val platformPrefix: String,
  val executableFileName: String,
  val buildType: String,
  val buildNumber: String,
  val tag: String? = null
) {
  companion object {
    fun new(
      productCode: String,
      platformPrefix: String,
      executableFileName: String,
      jetBrainsCIBuildType: String? = null,
      buildNumber: String = ""
    ): IdeInfo {
      return IdeInfo(
        productCode = productCode,
        platformPrefix = platformPrefix,
        executableFileName = executableFileName,
        buildNumber = buildNumber,
        buildType = if (!jetBrainsCIBuildType.isNullOrBlank()) jetBrainsCIBuildType else ""
      )
    }

  }

  val installerFilePrefix
    get() = when (productCode) {
      "IU" -> "ideaIU"
      "IC" -> "ideaIC"
      "WS" -> "WebStorm"
      "PS" -> "PhpStorm"
      "DB" -> "datagrip"
      "GO" -> "goland"
      "RM" -> "RubyMine"
      "PY" -> "pycharmPY"
      else -> error("Unknown product code: $productCode")
    }

  val installerProductName
    get() = when (productCode) {
      "IU" -> "intellij"
      "IC" -> "intellij.ce"
      "RM" -> "rubymine"
      "PY" -> "pycharm"
      else -> installerFilePrefix
    }

  val installerFileExt
    get() = when {
      SystemInfo.isWindows -> ".win.zip"
      SystemInfo.isLinux -> ".tar.gz"
      SystemInfo.isMac -> when (System.getProperty("os.arch")) {
        "x86_64" -> ".dmg"
        "aarch64" -> "-aarch64.dmg"
        else -> error("Unknown architecture of Mac OS")
      }
      else -> error("Unknown OS")
    }
}
