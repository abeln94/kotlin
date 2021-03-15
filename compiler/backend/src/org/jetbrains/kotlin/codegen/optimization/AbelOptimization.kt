/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.optimization

import org.jetbrains.kotlin.codegen.optimization.common.*
import org.jetbrains.kotlin.codegen.optimization.transformer.MethodTransformer
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.tree.*


const val ABEL_OPT_REMOVE_UNUSED = false
const val ABEL_OPT_INVERT_WHILE = true


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

        if (ABEL_OPT_INVERT_WHILE) {
            // Find the next sequence of instructions      ||      and replace with
            //
            //          ... (preCode) ...                                ... (preCode) ...
            //
            //    /-> branchLabel                      |
            //    |   ... (branchCode) ...             |\      /|  gotoInstruction -> branchLabel      >-\
            //  /-+-< branchInstruction -> postLabel   | \    /                                          |
            //  | |                                       \  /                                           |
            //  | |   *codeLabel*                          \/      codeLabel                           <-+-\
            //  | |   ... (codeCode) ...                   /\      ... (codeCode) ...                    | |
            //  | |                                       /  \                                           | |
            //  | |                                      /    \ |  branchLabel                         <-/ |
            //  | \-< gotoInstruction -> branchLabel   |/      \|  ... (branchCode) ...                    |
            //  |                                               |  **branchInstruction -> codelabel**  >---/
            //  |
            //  \---> postLabel                                    postLabel
            //        ... (postCode) ...                           ... (postCode) ...
            //
            //
            // * if there is no label, create one
            // ** change branchInstruction to the opposite (jump is true->jump if false) and label to codeLabel
            //
            val instructionsArray = instructions.asSequence()
            for (node in instructionsArray) {

                // get gotoInstruction and postLabel
                val gotoInstruction = node.takeIf { it.opcode == Opcodes.GOTO } as? JumpInsnNode ?: continue
                val postLabel = gotoInstruction.next as? LabelNode ?: continue

                // get branchLabel and branchInstruction
                val branchLabel = gotoInstruction.label
                val branchInstruction = branchLabel.findNextOrNull {
                    it.isIF // must be conditional
                            && (it as JumpInsnNode).label == postLabel // the jump destiny must be the after-goto
                            && instructionsArray.indexOf(it) < instructionsArray.indexOf(gotoInstruction) // and must be before the goto part
                } as? JumpInsnNode ?: continue

                // get codeLabel
                val codeLabel = branchInstruction.next as? LabelNode // there is already a label
                    ?: LabelNode(Label()).also { instructions.insert(branchInstruction, it) } // not a label, so create one

                //println("Detected optimization! ${branchLabel.debugText}::${branchInstruction.debugText} <-> ${gotoInstruction.debugText}")

                // move gotoInstruction before branchLabel
                instructions.moveBlock(gotoInstruction, gotoInstruction, branchLabel)
                // then move branchLabel-branchInstruction before postLabel
                instructions.moveBlock(branchLabel, branchInstruction, postLabel)
                // and finally change the condition and label of branchInstruction
                instructions.set(branchInstruction, JumpInsnNode(branchInstruction.opcode.negateIF, codeLabel))

                println("ABEL_OPT_INVERT_WHILE")
            }
        }

        println("</Abel Naya>")
    }
}

/**
 * Move all the instructions between 'from' and 'to' (both inclusive) before 'before'
 */
fun InsnList.moveBlock(from: AbstractInsnNode, to: AbstractInsnNode, before: AbstractInsnNode) {
    var marker = from
    while (true) {
        val nextMarker = marker.next
        this.remove(marker)
        this.insertBefore(before, marker)

        if (marker == to) return
        marker = nextMarker
    }
}

/**
 * Return true iff this is an if instruction
 */
val AbstractInsnNode.isIF
    get() = try {
        this.opcode.negateIF
        true
    } catch (e: RuntimeException) {
        false
    }

/**
 * Return the negation of an if opcode (throws runtime if not an if instruction)
 */
private val Int.negateIF
    get() = when (this) {
        Opcodes.IFEQ -> Opcodes.IFNE
        Opcodes.IFNE -> Opcodes.IFEQ

        Opcodes.IFLT -> Opcodes.IFGE
        Opcodes.IFGE -> Opcodes.IFLT

        Opcodes.IFGT -> Opcodes.IFLE
        Opcodes.IFLE -> Opcodes.IFGT

        Opcodes.IF_ICMPEQ -> Opcodes.IF_ICMPNE
        Opcodes.IF_ICMPNE -> Opcodes.IF_ICMPEQ

        Opcodes.IF_ICMPLT -> Opcodes.IF_ICMPGE
        Opcodes.IF_ICMPGE -> Opcodes.IF_ICMPLT

        Opcodes.IF_ICMPGT -> Opcodes.IF_ICMPLE
        Opcodes.IF_ICMPLE -> Opcodes.IF_ICMPGT

        Opcodes.IF_ACMPEQ -> Opcodes.IF_ACMPNE
        Opcodes.IF_ACMPNE -> Opcodes.IF_ACMPEQ

        Opcodes.IFNULL -> Opcodes.IFNONNULL
        Opcodes.IFNONNULL -> Opcodes.IFNULL

        else -> throw RuntimeException()
    }
