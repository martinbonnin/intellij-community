package com.intellij.ide.starter.ide

import com.intellij.ide.starter.models.IdeInfo

interface IdeInstallator {

  fun install(ideInfo: IdeInfo): Pair<String, InstalledIDE>

}