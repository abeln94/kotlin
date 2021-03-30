/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.lower.optimizations.FoldConstantLowering
import org.jetbrains.kotlin.backend.common.phaser.makeIrFilePhase
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.descriptors.impl.VariableDescriptorWithInitializerImpl
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

internal val abelPhase = makeIrFilePhase(
    ::AbelLowering,
    name = "Abel",
    description = "Lowering of Abel Naya"
)

class AbelLowering(val context: JvmBackendContext) : IrElementTransformerVoid(), BodyLoweringPass {
    val foldConstant = FoldConstantLowering(context)

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        while (true) {
            val inlined: MutableList<IrValueDeclaration> = mutableListOf()

            // replace getters of IrConst vals with its initializer
            irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
                override fun visitGetValue(expression: IrGetValue): IrExpression {
                    // the variable
                    val owner = expression.symbol.owner
                    // replace if it is a val and IrConst
                    return ((owner.takeIf { it.isVal } as? IrVariable)?.initializer as? IrConst<*>)
                        ?.copyWithOffsets(expression.startOffset, expression.endOffset)
                        ?.also {
                            // mark
                            if (!inlined.contains(owner)) inlined.add(owner)
                            println("ABEL_LOW_REPLACE_CONST")
                        }
                        ?: super.visitGetValue(expression) // else keep
                }
            })

            // remove inlined variables
            irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
                override fun visitDeclaration(declaration: IrDeclarationBase): IrStatement {
                    // if this is one of the inlined variables, replace with an empty block
                    return if (inlined.contains(declaration)) IrBlockImpl(
                        declaration.startOffset,
                        declaration.endOffset,
                        context.irBuiltIns.unitType
                    ) else super.visitDeclaration(declaration) // else keep
                }
            })

            // if no modification was made, exit
            if (inlined.isEmpty()) break

            // else run foldConstant and repeat
            foldConstant.lower(irBody, container)  // irBody.transformChildrenVoid(foldConstant) doesn't work :(

        }
    }
}

private fun <T> IrConst<T>.copyWithOffsets(startOffset: Int, endOffset: Int) =
    IrConstImpl(startOffset, endOffset, type, kind, value)

val IrValueDeclaration.isVal: Boolean
    get() = (this.symbol.descriptor as? VariableDescriptorWithInitializerImpl)?.isVar?.not() ?: false


// (expression.symbol.owner as? IrVariable)?.initializer?.takeIf { it.isSafeToUseWithoutCopying() }