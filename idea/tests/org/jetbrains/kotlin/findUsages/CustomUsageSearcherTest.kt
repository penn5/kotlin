/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.findUsages

import com.intellij.find.findUsages.CustomUsageSearcher
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.maskExtensions
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCaseBase

class CustomUsageSearcherTest : KotlinLightCodeInsightFixtureTestCaseBase() {

    fun testAddCustomUsagesForKotlin() {
        val customUsageSearcher = MyCustomUsageSearcher()
        maskExtensions(CustomUsageSearcher.EP_NAME, listOf(customUsageSearcher), testRootDisposable)
        myFixture.configureByText(KotlinFileType.INSTANCE, """val <caret>selfUsed = 1""")

        val usages = myFixture.getUsageViewTreeTextRepresentation(myFixture.elementAtCaret)
        assertTrue(usages.contains("val selfUsed"))
    }
}