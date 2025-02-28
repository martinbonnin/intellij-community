// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.uast.kotlin

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.uast.UastQualifiedExpressionAccessType

@ApiStatus.Internal
object KotlinQualifiedExpressionAccessTypes {
    @JvmField
    val SAFE = UastQualifiedExpressionAccessType("?.")
}
