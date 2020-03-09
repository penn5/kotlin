/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.proxy

import com.intellij.debugger.engine.JVMStackFrameInfoProvider
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.ui.impl.watch.StackFrameDescriptorImpl
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XValueChildrenList
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.stackFrame.KotlinStackFrame

/**
 * Coroutine exit frame represented by a stack frames
 * invokeSuspend():-1
 * resumeWith()
 *
 */

class CoroutinePreflightFrame(
    val invokeSuspendFrame: StackFrameProxyImpl,
    val resumeWithFrame: StackFrameProxyImpl,
    val preflightIndex: Int,
    val coroutineStackFrame: List<CoroutineStackFrameItem>,
    val stackFrameDescriptorImpl: StackFrameDescriptorImpl,
    val firstRestoredFrame: CoroutineStackFrameItem
) : KotlinStackFrame(stackFrameDescriptorImpl), JVMStackFrameInfoProvider {

    override fun computeChildren(node: XCompositeNode) {
        val childrenList = XValueChildrenList()
        firstRestoredFrame.spilledVariables.forEach {
            childrenList.add(it)
        }
        node.addChildren(childrenList, false)
        super.computeChildren(node)
    }

    override fun isInLibraryContent() =
        false

    override fun isSynthetic() =
        false

}