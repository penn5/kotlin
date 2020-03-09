/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.view

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.DoubleClickListener
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl
import org.jetbrains.kotlin.idea.debugger.coroutine.data.*
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.ApplicationThreadExecutor
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.ContinuationHolder
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.ManagerThreadExecutor
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.suspendContextImpl
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

class XDebuggerTreeSelectedNodeListener(val session: XDebugSession, val tree: XDebuggerTree) {
    val applicationThreadExecutor = ApplicationThreadExecutor()
    val javaDebugProcess = session.debugProcess as JavaDebugProcess
    val debugProcess: DebugProcessImpl = javaDebugProcess.debuggerSession.process
    val managerThreadExecutor = ManagerThreadExecutor(debugProcess)

    fun installOn() {
        object : DoubleClickListener() {
            override fun onDoubleClick(e: MouseEvent) =
                nodeSelected(KeyMouseEvent(e))
        }.installOn(tree)

        tree.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    val key = e.keyCode
                    if (key == KeyEvent.VK_ENTER || key == KeyEvent.VK_SPACE || key == KeyEvent.VK_RIGHT)
                        nodeSelected(KeyMouseEvent(e))
                }
            },
        )
    }

    fun nodeSelected(event: KeyMouseEvent): Boolean {
        val selectedNodes = tree.getSelectedNodes(XValueNodeImpl::class.java, null)
        if (selectedNodes.size == 1) {
            val node = selectedNodes[0]
            val valueContainer = node.valueContainer
            val suspendContext = session.suspendContextImpl()
            if (valueContainer is XCoroutineView.CoroutineFrameValue) {
                val frame = valueContainer.frame
                when (frame) {
                    is RunningCoroutineStackFrameItem -> {
                        val threadProxy = frame.frame.threadProxy()
                        val isCurrentContext = suspendContext.thread == threadProxy
                        createStackAndSetFrame(suspendContext, threadProxy, { frame.stackFrame }, isCurrentContext)
                    }
                    is CreationCoroutineStackFrameItem -> {
                        val position =
                            getPosition(frame.stackTraceElement.className, frame.stackTraceElement.lineNumber) ?: return false
                        val threadProxy = suspendContext.thread as ThreadReferenceProxyImpl
                        createStackAndSetFrame(suspendContext, threadProxy, {
                            SyntheticStackFrame(
                                frame.emptyDescriptor(),
                                emptyList(),
                                position
                            )
                        })
                    }
                    is SuspendCoroutineStackFrameItem -> {
                        val threadProxy = suspendContext.thread as ThreadReferenceProxyImpl
                        createStackAndSetFrame(suspendContext, threadProxy, { createSyntheticStackFrame(suspendContext, frame) })
                    }
                    is RestoredCoroutineStackFrameItem -> {
                        val threadProxy = frame.frame.threadProxy()
                        val position = getPosition(frame.location.declaringType().name(), frame.location.lineNumber()) ?: return false
                        createStackAndSetFrame(suspendContext,
                                               threadProxy,
                                               {
                                                   SyntheticStackFrame(
                                                       frame.emptyDescriptor(),
                                                       frame.spilledVariables,
                                                       position
                                                   )
                                               }
                        )

                    }
                    else -> {
                    }
                }
            }
        }
        return false
    }

    fun createStackAndSetFrame(
        suspendContext: SuspendContextImpl,
        threadReferenceProxy: ThreadReferenceProxyImpl,
        stackFrameProvider: () -> XStackFrame?,
        isCurrentContext: Boolean = false
    ) {
        managerThreadExecutor.on(suspendContext).schedule {
            val stackFrame = stackFrameProvider.invoke()
            if (stackFrame is XStackFrame) {
                val executionStack = createExecutionStack(threadReferenceProxy, isCurrentContext)
                applicationThreadExecutor.schedule(
                    {
                        session.setCurrentStackFrame(executionStack, stackFrame)
                    },
                    tree
                )
            }
        }
    }

    private fun createExecutionStack(proxy: ThreadReferenceProxyImpl, isCurrentContext: Boolean = false): XExecutionStack {
        val executionStack = CoroutineDebuggerExecutionStack(proxy, debugProcess, isCurrentContext)
        executionStack.initTopFrame()
        return executionStack
    }

    private fun getPosition(className: String, lineNumber: Int): XSourcePosition? {
        val psiFacade = JavaPsiFacade.getInstance(session.project)
        val psiClass = psiFacade.findClass(
            className.substringBefore("$"), // find outer class, for which psi exists TODO
            GlobalSearchScope.everythingScope(session.project)
        )
        val classFile = psiClass?.containingFile?.virtualFile
        // to convert to 0-based line number or '-1' to do not move
        val lineNumber = if (lineNumber > 0) lineNumber - 1 else return null
        return XDebuggerUtil.getInstance().createPosition(classFile, lineNumber)
    }

    private fun createSyntheticStackFrame(
        suspendContext: SuspendContextImpl,
        frame: SuspendCoroutineStackFrameItem
    ): SyntheticStackFrame? {
        val position =
            applicationThreadExecutor.readAction { getPosition(frame.stackTraceElement.className, frame.stackTraceElement.lineNumber) }
                ?: return null
        val continuation =
            ContinuationHolder.lookup(suspendContext, frame.lastObservedFrameFieldRef)
                ?: return null

        return SyntheticStackFrame(
            frame.emptyDescriptor(),
            continuation.getSpilledVariables() ?: return null,
            position
        )
    }
}

data class KeyMouseEvent(val keyEvent: KeyEvent?, val mouseEvent: MouseEvent?) {
    constructor(keyEvent: KeyEvent) : this(keyEvent, null)
    constructor(mouseEvent: MouseEvent) : this(null, mouseEvent)

    fun isKeyEvent() = keyEvent != null

    fun isMouseEvent() = mouseEvent != null
}