package com.intellij.codeInspection.tests.test.junit

import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.PathUtil
import junit.framework.TestCase
import java.io.File

internal fun ModifiableRootModel.addJUnit3Library() {
  val jar = File(PathUtil.getJarPathForClass(TestCase::class.java))
  PsiTestUtil.addLibrary(this, "junit3", jar.parent, jar.name)
}

internal fun ModifiableRootModel.addJUnit4Library() {
  val jar = File(PathUtil.getJarPathForClass(org.junit.Test::class.java))
  PsiTestUtil.addLibrary(this, "junit4", jar.parent, jar.name)
}

internal fun ModifiableRootModel.addHamcrest() {
  val jar = File(PathUtil.getJarPathForClass(org.hamcrest.MatcherAssert::class.java))
  PsiTestUtil.addLibrary(this, "hamcrest-core", jar.parent, jar.name)
}

internal fun ModifiableRootModel.addJUnit5Library() {
  val jupiterJar = File(PathUtil.getJarPathForClass(org.junit.jupiter.api.Test::class.java))
  PsiTestUtil.addLibrary(this, "junit5-jupiter", jupiterJar.parent, jupiterJar.name)
  val paramsJar = File(PathUtil.getJarPathForClass(org.junit.jupiter.params.ParameterizedTest::class.java))
  PsiTestUtil.addLibrary(this, "junit5-params", paramsJar.parent, paramsJar.name)
  val platformJar = File(PathUtil.getJarPathForClass(org.junit.platform.commons.annotation.Testable::class.java))
  PsiTestUtil.addLibrary(this, "junit-platform", platformJar.parent, platformJar.name)
}