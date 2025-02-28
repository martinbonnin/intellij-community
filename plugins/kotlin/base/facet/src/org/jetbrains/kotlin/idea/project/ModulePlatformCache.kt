// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.project

import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.workspaceModel.ide.WorkspaceModelTopics
import org.jetbrains.kotlin.config.KotlinFacetSettingsProvider
import org.jetbrains.kotlin.idea.base.facet.platform.platform
import org.jetbrains.kotlin.idea.base.util.caching.FineGrainedEntityCache
import org.jetbrains.kotlin.idea.base.util.caching.ModuleEntityChangeListener
import org.jetbrains.kotlin.platform.DefaultIdeTargetPlatformKindProvider
import org.jetbrains.kotlin.platform.TargetPlatform

class ModulePlatformCache(project: Project): FineGrainedEntityCache<Module, TargetPlatform>(project, cleanOnLowMemory = false) {
    override fun subscribe() {
        val busConnection = project.messageBus.connect(this)
        WorkspaceModelTopics.getInstance(project).subscribeImmediately(busConnection, ModelChangeListener(project))
    }

    override fun checkKeyValidity(key: Module) {
        if (key.isDisposed) {
            throw IllegalStateException("Module ${key.name} is already disposed")
        }
    }

    override fun calculate(key: Module): TargetPlatform {
        return KotlinFacetSettingsProvider.getInstance(key.project)?.getInitializedSettings(key)?.targetPlatform
            ?: key.project.platform
            ?: DefaultIdeTargetPlatformKindProvider.defaultPlatform
    }

    internal class ModelChangeListener(project: Project) : ModuleEntityChangeListener(project) {
        override fun entitiesChanged(outdated: List<Module>) {
            val platformCache = getInstance(project)

            platformCache.invalidateKeys(outdated)
        }
    }

    companion object {
        fun getInstance(project: Project): ModulePlatformCache = project.service()
    }
}