package org.jetbrains.kotlin.backend.common.serialization

import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrPropertyImpl
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.wrapInDelegatedSymbol
import org.jetbrains.kotlin.ir.util.*


class FakeOverrideCopier(
    val symbolRemapper: SymbolRemapper,
    val typeRemapper: TypeRemapper,
    val symbolRenamer: SymbolRenamer
) : DeepCopyIrTreeWithSymbols(symbolRemapper, typeRemapper, symbolRenamer) {

    private fun <T : IrFunction> T.transformFunctionChildren(declaration: T): T =
        apply {
            transformAnnotations(declaration)
            copyTypeParametersFrom(declaration)
            typeRemapper.withinScope(this) {
                // This is the more correct way to produce dispatch receiver for a fake override,
                // but some lowerings still expect the below behavior.
                /*
                val superDispatchReceiver = declaration.dispatchReceiverParameter!!
                val dispatchReceiverSymbol = IrValueParameterSymbolImpl(WrappedReceiverParameterDescriptor())
                val dispatchReceiverType = destinationClass.defaultType
                dispatchReceiverParameter = IrValueParameterImpl(
                    superDispatchReceiver.startOffset,
                    superDispatchReceiver.endOffset,
                    superDispatchReceiver.origin,
                    dispatchReceiverSymbol,
                    superDispatchReceiver.name,
                    superDispatchReceiver.index,
                    dispatchReceiverType,
                    null,
                    superDispatchReceiver.isCrossinline,
                    superDispatchReceiver.isNoinline
                )
                */
                // Should fake override's receiver be the current class is an open question.
                dispatchReceiverParameter = declaration.dispatchReceiverParameter?.transform()
                extensionReceiverParameter = declaration.extensionReceiverParameter?.transform()
                returnType = typeRemapper.remapType(declaration.returnType)
                this.valueParameters = declaration.valueParameters.transform()
            }
        }

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrSimpleFunction =
        IrFunctionImpl(
            declaration.startOffset, declaration.endOffset,
            IrDeclarationOrigin.FAKE_OVERRIDE,
            (wrapInDelegatedSymbol(symbolRemapper.getDeclaredFunction(declaration.symbol)) as IrSimpleFunctionSymbol),
            symbolRenamer.getFunctionName(declaration.symbol),
            //INHERITED,
            declaration.visibility,
            declaration.modality,
            declaration.returnType,
            isInline = declaration.isInline,
            isExternal = declaration.isExternal,
            isTailrec = declaration.isTailrec,
            isSuspend = declaration.isSuspend,
            isExpect = declaration.isExpect,
            isFakeOverride = true,
            isOperator = declaration.isOperator
        ).apply {
            transformFunctionChildren(declaration)
        }

    override fun visitProperty(declaration: IrProperty): IrProperty =
        IrPropertyImpl(
            declaration.startOffset, declaration.endOffset,
            IrDeclarationOrigin.FAKE_OVERRIDE,
            (wrapInDelegatedSymbol(symbolRemapper.getDeclaredProperty(declaration.symbol)) as IrPropertySymbol),
            declaration.name,
            //INHERITED,
            declaration.visibility,
            declaration.modality,
            isVar = declaration.isVar,
            isConst = declaration.isConst,
            isLateinit = declaration.isLateinit,
            isDelegated = declaration.isDelegated,
            isExpect = declaration.isExpect,
            isExternal = declaration.isExternal
        ).apply {
            transformAnnotations(declaration)
            this.getter = declaration.getter?.transform()
            this.setter = declaration.setter?.transform()
        }
}