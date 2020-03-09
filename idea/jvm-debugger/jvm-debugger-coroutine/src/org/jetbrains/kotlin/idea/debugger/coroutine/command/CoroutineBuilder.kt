/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.command

import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.JavaExecutionStack
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.jdi.ClassesByNameProvider
import com.intellij.debugger.jdi.GeneratedLocation
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.util.containers.ContainerUtil
import com.sun.jdi.*
import org.jetbrains.kotlin.idea.debugger.coroutine.CoroutineAsyncStackTraceProvider
import org.jetbrains.kotlin.idea.debugger.coroutine.data.*
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.isPreFlight
import org.jetbrains.kotlin.idea.debugger.safeLineNumber
import org.jetbrains.kotlin.idea.debugger.safeLocation
import org.jetbrains.kotlin.idea.debugger.safeMethod


class CoroutineBuilder(val suspendContext: SuspendContextImpl) {
    private val coroutineStackFrameProvider = CoroutineAsyncStackTraceProvider()
    val debugProcess = suspendContext.debugProcess
    private val virtualMachineProxy = debugProcess.virtualMachineProxy
    private val classesByName = ClassesByNameProvider.createCache(virtualMachineProxy.allClasses())

    companion object {
        const val CREATION_STACK_TRACE_SEPARATOR = "\b\b\b" // the "\b\b\b" is used as creation stacktrace separator in kotlinx.coroutines
    }

    fun build(coroutine: CoroutineInfoData): List<CoroutineStackFrameItem> {
        val coroutineStackFrameList = mutableListOf<CoroutineStackFrameItem>()
        val topRunningFrame = suspendContext.thread?.forceFrames()?.first() ?: return coroutineStackFrameList
        val creationFrameSeparatorIndex = findCreationFrameIndex(coroutine.stackTrace)

        if (coroutine.state == CoroutineInfoData.State.RUNNING && coroutine.activeThread is ThreadReference) {
            val threadReferenceProxyImpl = ThreadReferenceProxyImpl(debugProcess.virtualMachineProxy, coroutine.activeThread)
            val executionStack = JavaExecutionStack(threadReferenceProxyImpl, debugProcess, suspendedSameThread(coroutine.activeThread))

            val realFrames = threadReferenceProxyImpl.forceFrames()
            var coroutineStackInserted = false
            var preflightFound = false
            for (i in 0 until realFrames.size) {
                val runningStackFrameProxy = realFrames[i]
                val jStackFrame = executionStack.createStackFrame(runningStackFrameProxy)
                if (runningStackFrameProxy.location().isPreFlight()) {
                    preflightFound = true
                    continue
                }
                if (preflightFound) {
                    val coroutineStack = coroutineStackFrameProvider.lookupForResumeContinuation(runningStackFrameProxy, suspendContext)
                    if (coroutineStack?.isNotEmpty() == true) {
                        // clue coroutine stack into the thread's real stack

                        for (asyncFrame in coroutineStack) {
                            coroutineStackFrameList.add(
                                RestoredCoroutineStackFrameItem(
                                    runningStackFrameProxy,
                                    asyncFrame.location,
                                    asyncFrame.spilledVariables
                                )
                            )
                            coroutineStackInserted = true
                        }
                    }
                    preflightFound = false
                }
                if (!(coroutineStackInserted && isInvokeSuspendNegativeLineMethodFrame(runningStackFrameProxy)))
                    coroutineStackFrameList.add(
                        RunningCoroutineStackFrameItem(
                            runningStackFrameProxy,
                            jStackFrame
                        )
                    )
                coroutineStackInserted = false
            }
        } else if ((coroutine.state == CoroutineInfoData.State
                .SUSPENDED || coroutine.activeThread == null) && coroutine.lastObservedFrameFieldRef is ObjectReference
        ) {
            // to get frames from CoroutineInfo anyway
            // the thread is paused on breakpoint - it has at least one frame
            val suspendedStackTrace = coroutine.stackTrace.take(creationFrameSeparatorIndex)
            for (suspendedFrame in suspendedStackTrace) {
                val location = createLocation(suspendedFrame)
                coroutineStackFrameList.add(
                    SuspendCoroutineStackFrameItem(
                        topRunningFrame,
                        suspendedFrame,
                        coroutine.lastObservedFrameFieldRef,
                        location
                    )
                )
            }
        }

        coroutine.stackTrace.subList(creationFrameSeparatorIndex + 1, coroutine.stackTrace.size).forEach {
            val location = createLocation(it)
            coroutineStackFrameList.add(
                CreationCoroutineStackFrameItem(
                    topRunningFrame,
                    it,
                    location
                )
            )
        }
        coroutine.stackFrameList.addAll(coroutineStackFrameList)
        return coroutineStackFrameList
    }

    private fun suspendedSameThread(activeThread: ThreadReference) =
        activeThread == suspendContext.thread?.threadReference

    private fun createLocation(stackTraceElement: StackTraceElement): Location = findLocation(
        ContainerUtil.getFirstItem(classesByName[stackTraceElement.className]),
        stackTraceElement.methodName,
        stackTraceElement.lineNumber
    )

    private fun findLocation(
        type: ReferenceType?,
        methodName: String,
        line: Int
    ): Location {
        if (type != null && line >= 0) {
            try {
                val location = type.locationsOfLine(DebugProcess.JAVA_STRATUM, null, line).stream()
                    .filter { l: Location -> l.method().name() == methodName }
                    .findFirst().orElse(null)
                if (location != null) {
                    return location
                }
            } catch (ignored: AbsentInformationException) {
            }
        }
        return GeneratedLocation(debugProcess, type, methodName, line)
    }

    /**
     * Tries to find creation frame separator if any, returns last index if none found
     */
    private fun findCreationFrameIndex(frames: List<StackTraceElement>): Int {
        val index = frames.indexOfFirst { isCreationSeparatorFrame(it) }
        return if (index < 0)
            frames.lastIndex
        else
            index
    }

    private fun isCreationSeparatorFrame(it: StackTraceElement) =
        it.className.startsWith(CREATION_STACK_TRACE_SEPARATOR)

    private fun isInvokeSuspendNegativeLineMethodFrame(frame: StackFrameProxyImpl) =
        frame.safeLocation()?.safeMethod()?.name() == "invokeSuspend" &&
                frame.safeLocation()?.safeMethod()?.signature() == "(Ljava/lang/Object;)Ljava/lang/Object;" &&
                frame.safeLocation()?.safeLineNumber() ?: 0 < 0
}