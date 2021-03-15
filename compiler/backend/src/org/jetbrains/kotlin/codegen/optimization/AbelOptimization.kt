/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.optimization

import org.jetbrains.kotlin.codegen.optimization.common.*
import org.jetbrains.kotlin.codegen.optimization.transformer.MethodTransformer
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.tree.*


const val ABEL_OPT_REMOVE_UNUSED = true


class AbelOptimization : MethodTransformer() {

    override fun transform(internalClassName: String, methodNode: MethodNode) {
        println("<Abel Naya>")
        val instructions = methodNode.instructions

        if (ABEL_OPT_REMOVE_UNUSED) {
            // remove ICONST + ISTORE without any ILOAD
            val loads = instructions.asSequence()
                .filter { it.isLoadOperation() } // get loads
                .map { it as VarInsnNode }
                .map { it.`var` } // get its variable index
            instructions.asSequence()
                .filter { it.isStoreOperation() && it.previous.intConstant != null } // get stores preceded by an int constant
                .map { it as VarInsnNode }
                .filter { !loads.contains(it.`var`) } // which are not in the loads list
                .forEach {
                    // remove (with the previous int constant)
                    instructions.set(it.previous, InsnNode(Opcodes.NOP))
                    instructions.remove(it)
                    println("ABEL_OPT_REMOVE_UNUSED")
                }
        }

        println("</Abel Naya>")
    }
}
