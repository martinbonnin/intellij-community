/*******************************************************************************
 * Copyright 2000-2022 JetBrains s.r.o. and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.jetbrains.packagesearch.intellij.plugin.extensibility

import com.intellij.buildsystem.model.OperationFailure
import com.intellij.buildsystem.model.OperationItem
import com.intellij.buildsystem.model.unified.UnifiedDependency
import com.intellij.buildsystem.model.unified.UnifiedDependencyRepository
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.jetbrains.packagesearch.intellij.plugin.intentions.PackageSearchDependencyUpgradeQuickFix

/**
 * Extension point that allows to modify the dependencies of a specific project.
 */
interface ProjectModuleOperationProvider {

    companion object {

        private val extensions: Array<ProjectModuleOperationProvider>
            get() = runCatching {
                ExtensionPointName.create<ProjectModuleOperationProvider>("com.intellij.packagesearch.projectModuleOperationProvider")
                    .extensions
            }.getOrDefault(emptyArray())

        /**
         * Retrieves the first provider for given [project] and [psiFile].
         * @return The first compatible provider or `null` if none is found.
         */
        fun forProjectPsiFileOrNull(project: Project, psiFile: PsiFile?): ProjectModuleOperationProvider? =
            extensions.firstOrNull { it.hasSupportFor(project, psiFile) }

        /**
         * Retrieves the first provider for given the [projectModuleType].
         * @return The first compatible provider or `null` if none is found.
         */
        fun forProjectModuleType(projectModuleType: ProjectModuleType): ProjectModuleOperationProvider? =
            extensions.firstOrNull { it.hasSupportFor(projectModuleType) }
    }

    /**
     * Returns whether the implementation of the interface uses the shared "packages update available"
     * inspection and quickfix. This is `false` by default; override this property and return `true`
     * to opt in to [PackageUpdateInspection].
     *
     * @return `true` opt in to [PackageUpdateInspection], false otherwise.
     * @see PackageUpdateInspection
     * @see PackageSearchDependencyUpgradeQuickFix
     */
    fun usesSharedPackageUpdateInspection(): Boolean = false

    /**
     * Checks if current implementation has support in the given [project] for the current [psiFile].
     * @return `true` if the [project] and [psiFile] are supported.
     */
    fun hasSupportFor(project: Project, psiFile: PsiFile?): Boolean

    /**
     * Checks if current implementation has support in the given [projectModuleType].
     * @return `true` if the [projectModuleType] is supported.
     */
    fun hasSupportFor(projectModuleType: ProjectModuleType): Boolean

    /**
     * Adds a dependency to the given [module] using [operationMetadata].
     * @return A list containing all failures encountered during the operation. If the list is empty, the operation was successful.
     */
    fun addDependencyToModule(
        operationMetadata: DependencyOperationMetadata,
        module: ProjectModule
    ): List<OperationFailure<out OperationItem>>

    /**
     * Removes a dependency from the given [module] using [operationMetadata].
     * @return A list containing all failures encountered during the operation. If the list is empty, the operation was successful.
     */
    fun removeDependencyFromModule(
        operationMetadata: DependencyOperationMetadata,
        module: ProjectModule
    ): List<OperationFailure<out OperationItem>>

    /**
     * Modify a dependency in the given [module] using [operationMetadata].
     * @return A list containing all failures encountered during the operation. If the list is empty, the operation was successful.
     */
    fun updateDependencyInModule(
        operationMetadata: DependencyOperationMetadata,
        module: ProjectModule
    ): List<OperationFailure<out OperationItem>>

    /**
     * Lists all dependencies declared in the given [module]. A declared dependency
     * have to be explicitly written in the build file.
     * @return A [Collection]<[UnifiedDependency]> found in the project.
     */
    suspend fun declaredDependenciesInModule(
        module: ProjectModule
    ): Collection<UnifiedDependency>

    /**
     * Lists all resolved dependencies in the given [module].
     * @return A [Collection]<[UnifiedDependency]> found in the project.
     */
    suspend fun resolvedDependenciesInModule(
        module: ProjectModule,
        scopes: Set<String> = emptySet()
    ): Collection<UnifiedDependency>

    /**
     * Adds the [repository] to the given [module].
     * @return A list containing all failures encountered during the operation. If the list is empty, the operation was successful.
     */
    fun addRepositoryToModule(
        repository: UnifiedDependencyRepository,
        module: ProjectModule
    ): List<OperationFailure<out OperationItem>>

    /**
     * Removes the [repository] from the given [module].
     * @return A list containing all failures encountered during the operation. If the list is empty, the operation was successful.
     */
    fun removeRepositoryFromModule(
        repository: UnifiedDependencyRepository,
        module: ProjectModule
    ): List<OperationFailure<out OperationItem>>

    /**
     * Lists all repositories in the given [module].
     * @return A [Collection]<[UnifiedDependencyRepository]> found the project.
     */
    fun listRepositoriesInModule(
        module: ProjectModule
    ): Collection<UnifiedDependencyRepository>
}
