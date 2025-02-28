// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.refactoring.nameSuggester

import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.idea.base.fe10.codeInsight.newDeclaration.Fe10KotlinNameSuggester
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.util.CodeInsightUtils
import org.jetbrains.kotlin.idea.refactoring.IntroduceRefactoringException
import org.jetbrains.kotlin.idea.refactoring.selectElement
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.idea.test.KotlinTestUtils
import org.jetbrains.kotlin.test.TestMetadata
import org.jetbrains.kotlin.idea.test.TestRoot
import org.junit.internal.runners.JUnit38ClassRunner
import org.junit.runner.RunWith

@TestRoot("idea/tests")
@TestMetadata("testData/refactoring/nameSuggester")
@RunWith(JUnit38ClassRunner::class)
class KotlinNameSuggesterTest : KotlinLightCodeInsightFixtureTestCase() {
    fun testArrayList() = doTest()

    fun testGetterSure() = doTest()

    fun testNameArrayOfClasses() = doTest()

    fun testNameArrayOfStrings() = doTest()

    fun testNamePrimitiveArray() = doTest()

    fun testNameCallExpression() = doTest()

    fun testNameClassCamelHump() = doTest()

    fun testNameLong() = doTest()

    fun testNameReferenceExpression() = doTest()

    fun testNameReferenceExpressionForConstants() = doTest()

    fun testNameString() = doTest()

    fun testAnonymousObject() = doTest()

    fun testAnonymousObjectWithSuper() = doTest()

    fun testArrayOfObjectsType() = doTest()

    fun testURL() = doTest()

    fun testParameterNameByArgumentExpression() = doTest()

    fun testParameterNameByParenthesizedArgumentExpression() = doTest()

    fun testIdWithDigits() = doTest()

    fun testIdWithNonASCII() = doTest()

    fun testFunction1() = doTest()

    fun testFunction2() = doTest()

    fun testExtensionFunction1() = doTest()

    fun testExtensionFunction2() = doTest()

    fun testNoCamelNamesForBacktickedNonId() = doTest()

    fun testListOfInts() = doTest()

    fun testListOfClasses() = doTest()

    fun testStringIntMap() = doTest()

    fun testIterable() = doTest()

    private fun doTest() {
        try {
            myFixture.configureByFile(getTestName(false) + ".kt")
            val file = myFixture.file as KtFile
            val expectedResultText = KotlinTestUtils.getLastCommentInFile(file)

            selectElement(myFixture.editor, file, listOf(CodeInsightUtils.ElementKind.EXPRESSION)) {
                val names = Fe10KotlinNameSuggester
                    .suggestNamesByExpressionAndType(
                        it as KtExpression,
                        null,
                        it.analyze(BodyResolveMode.PARTIAL),
                        { true },
                        "value"
                    )
                    .sorted()
                val result = StringUtil.join(names, "\n").trim()
                assertEquals(expectedResultText, result)
            }
        } catch (e: IntroduceRefactoringException) {
            throw AssertionError("Failed to find expression: " + e.message)
        }
    }
}
