/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.proxy

import com.sun.jdi.*
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.evaluate.DefaultExecutionContext

class CoroutineNoLibraryProxy(val executionContext: DefaultExecutionContext) : CoroutineInfoProvider {
    override fun dumpCoroutinesInfo(): List<CoroutineInfoData> {
        val allClasses = executionContext.vm.allClasses()
        for (cls in allClasses) {
            println(cls)
        }
        val debugMetadataKtType = executionContext.findCoroutineMetadataType() ?: return emptyList()
        val vm = executionContext.vm
        val dcClassTypeList = executionContext.findAllDispatchedContinuation()
        if(dcClassTypeList?.size == 1) {
            val dcClassType = dcClassTypeList.first()
//            val fields = dcClassType.fields()
            val continuatonField = dcClassType.fieldByName("continuation")
            val continuationList = dcClassType.instances(1000)
            for(dispatchedContinuation in continuationList) {
//                val fieldValueMap = dispatchedContinuation.getValues(fields)
//                val continuationRefType = dispatchedContinuation.referenceType()
                val someContinuation = dispatchedContinuation.getValue(continuatonField) as ObjectReference
                val someContinuationRefType = executionContext.findAndInvoke(someContinuation, "getClass") as ClassObjectReference
                val annotationArray = executionContext.findAndInvoke(someContinuationRefType, "getAnnotations") as ArrayReference
                val annotationValues = annotationArray.values
                val classType = executionContext.findClass("java.lang.Class") as ClassType
                val cls = executionContext.findClass("kotlin.coroutines.jvm.internal.DebugMetadata") as InterfaceType
                cls.instances(1000)

                val lookingForClass = executionContext.invokeMethod(executionContext.findClass("java.lang.Class") as ClassType, classType.methodsByName("forName").first(), listOf(vm.mirrorOf("kotlin.coroutines.jvm.internal.DebugMetadata"))) as ClassObjectReference
                lookingForClass.type()
//                someContinuationRefType.
                val annots = executionContext.findAndInvoke(someContinuationRefType, "getAnnotationsByType",null, lookingForClass) as ArrayReference
                for (o in annots.values) {
                    if(o is ObjectReference) {
                        val variables = FieldVariable.extractFromContinuation(executionContext, someContinuation, debugMetadataKtType)

                        println("! found DebugMetadata and ${variables}")
                    }
                }
                for (annotation in annotationValues) {
                    if (annotation is ObjectReference) {
                        val annotationType = annotation.referenceType() as ClassType
                        for (inf in annotationType.interfaces()) {
                            if (inf.isDebugMetadata()) {

                                val annotationvals = annotation.getValues(annotationType.fields())
                                println("!" + annotationType +"  found DebugMetadata")
                            }
                        }
                    }
                }
//                "1".javaClass.getAnnotationsByType(Class.forName("1"))
                val annotations = executionContext.findAndInvoke(someContinuation, "getClass")
                println("!")
            }
        }
        return emptyList()
    }

}

//data class CoroutineInformation(val )

fun InterfaceType.isDebugMetadata() =
    "kotlin.coroutines.jvm.internal.DebugMetadata" == name()