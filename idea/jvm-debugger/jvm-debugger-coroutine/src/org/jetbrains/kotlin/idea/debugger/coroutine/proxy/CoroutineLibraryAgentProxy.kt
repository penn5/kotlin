/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.proxy

import com.intellij.debugger.engine.evaluation.EvaluateException
import com.sun.jdi.*
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.evaluate.DefaultExecutionContext

class CoroutineLibraryAgentProxy(private val debugProbesClsRef: ClassType, private val executionContext: DefaultExecutionContext) : CoroutineInfoProvider {
    private val debugProbesImplClsRef = executionContext.findClass("$DEBUG_PACKAGE.internal.DebugProbesImpl") as ClassType
    private val coroutineNameClsRef = executionContext.findClass("kotlinx.coroutines.CoroutineName") as ClassType
    private val classClsRef = executionContext.findClass("java.lang.Object") as ClassType
    private val debugProbesImplInstance = with(debugProbesImplClsRef) { getValue(fieldByName("INSTANCE")) as ObjectReference }
    private val enhanceStackTraceWithThreadDumpRef: Method = debugProbesImplClsRef
        .methodsByName("enhanceStackTraceWithThreadDump").single()

    private val dumpMethod: Method = debugProbesClsRef.concreteMethodByName("dumpCoroutinesInfo", "()Ljava/util/List;")
    val instance = with(debugProbesClsRef) { getValue(fieldByName("INSTANCE")) as ObjectReference }

    // CoroutineInfo
    private val coroutineInfoClsRef = executionContext.findClass("$DEBUG_PACKAGE.CoroutineInfo") as ClassType
    private val coroutineContextClsRef = executionContext.findClass("kotlin.coroutines.CoroutineContext") as InterfaceType

    private val getStateRef: Method = coroutineInfoClsRef.concreteMethodByName("getState", "()Lkotlinx/coroutines/debug/State;")
    private val getContextRef: Method = coroutineInfoClsRef.concreteMethodByName("getContext", "()Lkotlin/coroutines/CoroutineContext;")
    private val sequenceNumberFieldRef: Field = coroutineInfoClsRef.fieldByName("sequenceNumber")
    private val lastObservedStackTraceRef: Method = coroutineInfoClsRef.methodsByName("lastObservedStackTrace").single()
    private val getContextElement: Method = coroutineContextClsRef.methodsByName("get").single()
    private val getNameRef: Method = coroutineNameClsRef.methodsByName("getName").single()
    private val keyFieldRef = coroutineNameClsRef.fieldByName("Key")
    val toString: Method = classClsRef.concreteMethodByName("toString", "()Ljava/lang/String;")

    private val lastObservedThreadFieldRef: Field = coroutineInfoClsRef.fieldByName("lastObservedThread")
    private val lastObservedFrameFieldRef: Field = coroutineInfoClsRef.fieldByName("lastObservedFrame") // continuation

    // Methods for list
    private val listClsRef = executionContext.findClass("java.util.List") as InterfaceType
    private val sizeRef: Method = listClsRef.methodsByName("size").single()
    private val getRef: Method = listClsRef.methodsByName("get").single()
    private val stackTraceElementClsRef = executionContext.findClass("java.lang.StackTraceElement") as ClassType

    // for StackTraceElement
    private val methodNameFieldRef: Field = stackTraceElementClsRef.fieldByName("methodName")
    private val declaringClassFieldRef: Field = stackTraceElementClsRef.fieldByName("declaringClass")
    private val fileNameFieldRef: Field = stackTraceElementClsRef.fieldByName("fileName")
    private val lineNumberFieldRef: Field = stackTraceElementClsRef.fieldByName("lineNumber")

    // value
    private val keyFieldValueRef = coroutineNameClsRef.getValue(keyFieldRef) as ObjectReference

    @Synchronized
    @Suppress("unused")
    fun install() =
        executionContext.invokeMethodAsVoid(instance, "install")

    @Synchronized
    @Suppress("unused")
    fun uninstall() =
        executionContext.invokeMethodAsVoid(instance, "uninstall")

    override fun dumpCoroutinesInfo(): List<CoroutineInfoData> {
        val coroutinesInfo = executionContext.invokeMethodAsObject(instance, dumpMethod) ?: return emptyList()
        executionContext.keepReference(coroutinesInfo)
        val size = sizeOf(coroutinesInfo)

        return MutableList(size) {
            val elem = getElementFromList(coroutinesInfo, it)
            fetchCoroutineState(elem)
        }
    }

    private fun getElementFromList(instance: ObjectReference, num: Int) =
        executionContext.invokeMethod(
            instance, getRef,
            listOf(executionContext.vm.virtualMachine.mirrorOf(num))
        ) as ObjectReference

    private fun fetchCoroutineState(instance: ObjectReference): CoroutineInfoData {
        val name = getName(instance)
        val state = getState(instance)
        val thread = getLastObservedThread(instance, lastObservedThreadFieldRef)
        val lastObservedFrameFieldRef = instance.getValue(lastObservedFrameFieldRef) as? ObjectReference
        val stackTrace = getStackTrace(instance)
        return CoroutineInfoData(
            name,
            CoroutineInfoData.State.valueOf(state),
            stackTrace,
            thread,
            lastObservedFrameFieldRef
        )
    }

    private fun getName(
        info: ObjectReference // CoroutineInfo instance
    ): String {
        // equals to `coroutineInfo.context.get(CoroutineName).name`
        val coroutineContextInst = executionContext.invokeMethod(
            info,
            getContextRef,
            emptyList()
        ) as? ObjectReference ?: throw IllegalArgumentException("Coroutine context must not be null")
        val coroutineName = executionContext.invokeMethod(
            coroutineContextInst,
            getContextElement, listOf(keyFieldValueRef)
        ) as? ObjectReference
        // If the coroutine doesn't have a given name, CoroutineContext.get(CoroutineName) returns null
        val name = if (coroutineName != null) (executionContext.invokeMethod(
            coroutineName,
            getNameRef,
            emptyList()
        ) as StringReference).value() else "coroutine"
        val id = (info.getValue(sequenceNumberFieldRef) as LongValue).value()
        return "$name#$id"
    }

    private fun getState(
        info: ObjectReference // CoroutineInfo instance
    ): String {
        // equals to `stringState = coroutineInfo.state.toString()`
        val state = executionContext.invokeMethod(info, getStateRef, emptyList()) as ObjectReference
        return (executionContext.invokeMethod(state, toString, emptyList()) as StringReference).value()
    }

    private fun getLastObservedThread(
        info: ObjectReference, // CoroutineInfo instance
        threadRef: Field // reference to lastObservedThread
    ): ThreadReference? = info.getValue(threadRef) as? ThreadReference

    /**
     * Returns list of stackTraceElements for the given CoroutineInfo's [ObjectReference]
     */
    private fun getStackTrace(
        info: ObjectReference
    ): List<StackTraceElement> {
        val frameList = lastObservedStackTrace(info)
        val tmpList = mutableListOf<StackTraceElement>()
        for (it in 0 until sizeOf(frameList)) {
            val frame = getElementFromList(frameList, it)
            val ste = newStackTraceElement(frame)
            tmpList.add(ste)
        }
        val mergedFrameList = enhanceStackTraceWithThreadDump(listOf(info, frameList))
        val size = sizeOf(mergedFrameList)

        val list = mutableListOf<StackTraceElement>()

        for (it in 0 until size) {
            val frame = getElementFromList(mergedFrameList, it)
            val ste = newStackTraceElement(frame)
            list.add(// 0, // add in the beginning // @TODO what's the point?
                ste
            )
        }
        return list
    }

    private fun newStackTraceElement(frame: ObjectReference) =
        StackTraceElement(
            fetchClassName(frame),
            fetchMethodName(frame),
            fetchFileName(frame),
            fetchLine(frame)
        )

    private fun fetchLine(instance: ObjectReference) =
        (instance.getValue(lineNumberFieldRef) as? IntegerValue)?.value() ?: -1

    private fun fetchFileName(instance: ObjectReference) =
        (instance.getValue(fileNameFieldRef) as? StringReference)?.value() ?: ""

    private fun fetchMethodName(instance: ObjectReference) =
        (instance.getValue(methodNameFieldRef) as? StringReference)?.value() ?: ""

    private fun fetchClassName(instance: ObjectReference) =
        (instance.getValue(declaringClassFieldRef) as? StringReference)?.value() ?: ""

    private fun lastObservedStackTrace(instance: ObjectReference) =
        executionContext.invokeMethod(instance, lastObservedStackTraceRef, emptyList()) as ObjectReference

    private fun enhanceStackTraceWithThreadDump(args: List<ObjectReference>) =
        executionContext.invokeMethod(
            debugProbesImplInstance,
            enhanceStackTraceWithThreadDumpRef, args
        ) as ObjectReference

    private fun sizeOf(args: ObjectReference): Int =
        (executionContext.invokeMethod(args, sizeRef, emptyList()) as IntegerValue).value()

    companion object {
        private const val DEBUG_PACKAGE = "kotlinx.coroutines.debug"

        fun instance(executionContext: DefaultExecutionContext): CoroutineLibraryAgentProxy? {
            try {
                val debugProbesClsRef = executionContext.findClass("$DEBUG_PACKAGE.DebugProbes") ?: return null
                if (debugProbesClsRef is ClassType)
                    return CoroutineLibraryAgentProxy(debugProbesClsRef, executionContext)
            } catch (e: EvaluateException) {
            }
            return null
        }
    }

}
