// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.i18n

import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

@TestDataPath("\$CONTENT_ROOT/testData/inspections/propertyQuotesValidation/")
class DevKitPropertiesQuotesValidationInspectionTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getBasePath(): String {
    return DevkitI18nTestUtil.TESTDATA_PATH + "inspections/propertyQuotesValidation"
  }

  @Throws(Exception::class)
  override fun setUp() {
    super.setUp()
    myFixture.enableInspections(DevKitPropertiesQuotesValidationInspection())
  }

  fun testSimple() {
    myFixture.configureByFile("simple.properties")
    myFixture.checkHighlighting()
  }
}