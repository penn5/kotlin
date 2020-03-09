/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.proxy

import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.xdebugger.XDebugSession
import com.sun.jdi.*
import org.jetbrains.kotlin.idea.debugger.*
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.ContinuationHolder.Companion.BASE_CONTINUATION_IMPL_CLASS_NAME
import org.jetbrains.kotlin.idea.debugger.evaluate.DefaultExecutionContext

fun isSubtypeOfBaseContinuationImpl(type: Type) =
    type.isSubtype(BASE_CONTINUATION_IMPL_CLASS_NAME)

private fun isInvokeSuspendMethod(method: Method): Boolean =
    if (method.name() == "invokeSuspend")
        isSuspendLambda(method.declaringType()) && method.signature() == "(Ljava/lang/Object;)Ljava/lang/Object;"
    else
        false

/**
 * @TODO
 * Checks if the method has a Continuation as input parameter.
 */
private fun isSuspendMethod(method: Method): Boolean {
    val argTypes = method.argumentTypes()
    for (arg in argTypes) {
        println(arg)
    }
    return "Lkotlin/coroutines/Continuation;)" in method.signature()
}

private fun isSuspendLambda(referenceType: ReferenceType): Boolean =
    SUSPEND_LAMBDA_CLASSES.any { referenceType.isSubtype(it) }


fun Location.isPreFlight(): Boolean {
    val method = safeMethod() ?: return false
    return isInvokeSuspendMethod(method)
}

fun Location.isResumeWith() =
    safeMethod()?.name() == "resumeWith"

fun StackFrameProxyImpl.variableValue(variableName: String): ObjectReference? {
    val continuationVariable = safeVisibleVariableByName(variableName) ?: return null
    return getValue(continuationVariable) as? ObjectReference ?: return null
}

fun Location.isGetCOROUTINE_SUSPENDED() : Boolean =
    safeMethod()?.isGetCOROUTINE_SUSPENDED() ?: false

fun Method.isGetCOROUTINE_SUSPENDED() =
    signature() == "()Ljava/lang/Object;" && name() == "getCOROUTINE_SUSPENDED" && declaringType().name() == "kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt"

fun DefaultExecutionContext.findCoroutineMetadataType() =
    findClassSafe("kotlin.coroutines.jvm.internal.DebugMetadataKt")

fun DefaultExecutionContext.findAllDispatchedContinuation() =
        vm.classesByName("kotlinx.coroutines.DispatchedContinuation")

/**
 * Finds previous Continuation for this Continuation (completion field in BaseContinuationImpl)
 * @return null if given ObjectReference is not a BaseContinuationImpl instance or completion is null
 */
fun getNextFrame(context: DefaultExecutionContext, continuation: ObjectReference): ObjectReference? {
    if (!isSubtypeOfBaseContinuationImpl(continuation.type()))
        return null
    val type = continuation.type() as ClassType
    val next = type.concreteMethodByName("getCompletion", "()Lkotlin/coroutines/Continuation;")
    return context.invokeMethod(continuation, next, emptyList()) as? ObjectReference
}

fun SuspendContextImpl.executionContext() =
    DefaultExecutionContext(EvaluationContextImpl(this, this.frameProxy))

fun ThreadReferenceProxyImpl.supportsEvaluation(): Boolean =
    threadReference?.isSuspended ?: false

fun SuspendContextImpl.supportsEvaluation() =
    this.debugProcess.canRunEvaluation

fun XDebugSession.suspendContextImpl() =
    suspendContext as SuspendContextImpl

