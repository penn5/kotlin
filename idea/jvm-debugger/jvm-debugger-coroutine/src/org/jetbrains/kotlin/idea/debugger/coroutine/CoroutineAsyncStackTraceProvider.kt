/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine

import com.intellij.debugger.engine.AsyncStackTraceProvider
import com.intellij.debugger.engine.JavaStackFrame
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.jdi.StackFrameProxyImpl
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.*
import org.jetbrains.kotlin.idea.debugger.coroutine.data.AfterCoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.hopelessAware
import org.jetbrains.kotlin.idea.debugger.isInKotlinSources

class CoroutineAsyncStackTraceProvider : AsyncStackTraceProvider {

    override fun getAsyncStackTrace(stackFrame: JavaStackFrame, suspendContext: SuspendContextImpl): List<CoroutineStackFrameItem>? {
        val stackFrameList = hopelessAware {
            if (stackFrame is CoroutinePreflightFrame)
                lookupForAfterPreflight(stackFrame, suspendContext)
            else
                null
        }
        return if (stackFrameList == null || stackFrameList.isEmpty())
            null
        else stackFrameList
    }

    fun lookupForAfterPreflight(
        stackFrame: CoroutinePreflightFrame,
        suspendContext: SuspendContextImpl
    ): List<CoroutineStackFrameItem>? {
        val resumeWithFrame = stackFrame.resumeWithFrame

        if (threadAndContextSupportsEvaluation(suspendContext, resumeWithFrame)) {
            val stackFrames = mutableListOf<CoroutineStackFrameItem>()
            val threadProxy = resumeWithFrame.threadProxy()
            val frames = threadProxy.frames()
            if (stackFrame.coroutineStackFrame != null)
                stackFrames.addAll(stackFrame.coroutineStackFrame)

            for (index in stackFrame.preflightIndex + 2..frames.size - 1) {
                val frame = frames[index]
                stackFrames.add(AfterCoroutineStackFrameItem(frame))
            }
            return stackFrames
        }
        return null
    }

    fun lookupForPreflightFrame(
        frameProxy: StackFrameProxyImpl,
        suspendContext: SuspendContextImpl
    ): List<CoroutineStackFrameItem>? {
        val location = frameProxy.location()
        if (!location.isInKotlinSources())
            return null

        if (threadAndContextSupportsEvaluation(suspendContext, frameProxy)) {
            if (location.isPreFlight()) {
                val threadProxy = frameProxy.threadProxy()
                var frames = threadProxy.frames()
                val preflightIndex = frames.indexOf(frameProxy)
                val resumeWithFrame = frames[preflightIndex + 1]
                val stackFrame = mutableListOf<CoroutineStackFrameItem>()
                if (resumeWithFrame.location().isResumeWith()) {
                    var continuationStack = ContinuationHolder.lookupForResumeMethodContinuation(
                        suspendContext,
                        resumeWithFrame
                    )?.getAsyncStackTraceIfAny()
                    if (continuationStack != null)
                        stackFrame.addAll(continuationStack)
                }

                for (index in preflightIndex + 2..frames.size - 1) {
                    val frame = frames[index]
                    stackFrame.add(AfterCoroutineStackFrameItem(frame))
                }
                return stackFrame
            }
        }

        return null
    }

    fun lookupForResumeContinuation(
        frameProxy: StackFrameProxyImpl,
        suspendContext: SuspendContextImpl
    ): List<CoroutineStackFrameItem>? {
        val location = frameProxy.location()
        if (!location.isInKotlinSources())
            return null

        if (threadAndContextSupportsEvaluation(suspendContext, frameProxy))
            return ContinuationHolder.lookupForResumeMethodContinuation(suspendContext, frameProxy)?.getAsyncStackTraceIfAny()
        return null
    }

    private fun threadAndContextSupportsEvaluation(suspendContext: SuspendContextImpl, frameProxy: StackFrameProxyImpl) =
        suspendContext.supportsEvaluation() && frameProxy.threadProxy().supportsEvaluation()
}

