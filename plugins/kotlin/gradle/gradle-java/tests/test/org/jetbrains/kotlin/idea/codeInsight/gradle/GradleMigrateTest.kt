// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.codeInsight.gradle

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.runInEdtAndWait
import org.jetbrains.kotlin.idea.codeInsight.gradle.GradleKotlinTestUtils.TestedKotlinGradlePluginVersions
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinPluginLayout
import org.jetbrains.kotlin.idea.configuration.notifications.disableNewKotlinCompilerAvailableNotification
import org.jetbrains.kotlin.idea.notification.catchNotificationText
import org.jetbrains.plugins.gradle.tooling.annotation.TargetVersions
import org.junit.AssumptionViolatedException
import org.junit.Test

class GradleMigrateTest : MultiplePluginVersionGradleImportingTestCase() {

    @Test
    @TargetVersions("6.9+")
    fun testMigrateStdlib() {
        if (TestedKotlinGradlePluginVersions.ALL_PUBLIC.last() != kotlinPluginVersion) {
            if (IS_UNDER_TEAMCITY) return else throw AssumptionViolatedException("Ignored KGP version $kotlinPluginVersion")
        }

        val notificationText = doMigrationTest(
            beforeText = """
            buildscript {
                repositories {
                    ${GradleKotlinTestUtils.listRepositories(false, gradleVersion)}                    
                }
                dependencies {
                    classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:${TestedKotlinGradlePluginVersions.V_1_5_32}"
                }
            }

            apply plugin: 'kotlin'

            dependencies {
                implementation "org.jetbrains.kotlin:kotlin-stdlib:${TestedKotlinGradlePluginVersions.V_1_5_32}"
            }
            """,
            afterText =
            """
            buildscript {
                repositories {
                    ${GradleKotlinTestUtils.listRepositories(false, gradleVersion)}                    
                }
                dependencies {
                    classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:${TestedKotlinGradlePluginVersions.V_1_6_21}"
                }
            }

            apply plugin: 'kotlin'

            dependencies {
                implementation "org.jetbrains.kotlin:kotlin-stdlib:${TestedKotlinGradlePluginVersions.V_1_6_21}"
            }
            """
        )

        assertEquals(
            "Migrations for Kotlin code are available<br/><br/>Detected migration:<br/>&nbsp;&nbsp;Language version: 1.5 -> 1.6<br/>&nbsp;&nbsp;API version: 1.5 -> 1.6<br/>",
            notificationText,
        )
    }

    private fun doMigrationTest(beforeText: String, afterText: String): String? = catchNotificationText(myProject) {
        createProjectSubFile("settings.gradle", "include ':app'")
        val gradleFile = createProjectSubFile("app/build.gradle", beforeText.trimIndent())

        runInEdtAndWait {
            runWriteAction {
                disableNewKotlinCompilerAvailableNotification(KotlinPluginLayout.standaloneCompilerVersion.kotlinVersion)
            }
        }

        importProject()

        val document = runReadAction {
            val gradlePsiFile = PsiManager.getInstance(myProject).findFile(gradleFile) ?: error("Can't find psi file for gradle file")
            PsiDocumentManager.getInstance(myProject).getDocument(gradlePsiFile) ?: error("Can't find document for gradle file")
        }

        runInEdtAndWait {
            runWriteAction {
                document.setText(afterText.trimIndent())
            }
        }

        importProject()
    }
}
