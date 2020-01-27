package org.jetbrains.kotlin.backend.common.serialization

import org.jetbrains.kotlin.backend.common.DescriptorsToIrRemapper
import org.jetbrains.kotlin.backend.common.WrappedDescriptorPatcher
import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureSerializer
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrPropertyImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.descriptors.*
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.IrClassPublicSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.buildSimpleType
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.types.AbstractTypeCheckerContext

class FakeOverrideBuilder(val symbolTable: SymbolTable, val signaturer: IdSignatureSerializer, val irBuiltIns: IrBuiltIns) {
    //private val needFakeOverrides = mutableListOf<IrClass>()
    private val haveFakeOverrides = mutableSetOf<IrClass>()
    private val doDebug = false

    private inline fun debug(any: Any) = if (doDebug) println(any) else {}

    private fun fakeOverrideMember(superType: IrType, member: IrDeclaration, clazz: IrClass): IrDeclaration {
        if (superType !is IrSimpleType) error("superType is $superType, expected IrSimpleType")
        val classifier = superType.classifier
        if (classifier !is IrClassSymbol) error("superType classifier is not IrClassSymbol: ${classifier}")


        val typeParameters = classifier.owner.typeParameters.map { it.symbol }
        val typeArguments = superType.arguments.map { it as IrSimpleType } // TODO: the cast should not be here

        assert(typeParameters.size == typeArguments.size) {
            "typeParameters = $typeParameters size != typeArguments = $typeArguments size "
        }

        val substitutionMap = typeParameters.zip(typeArguments).toMap()
        val copier = DeepCopyIrTreeWithSymbolsForFakeOverrides(substitutionMap, superType, clazz)

        require(member is IrProperty || member is IrSimpleFunction)
        val deepCopyFakeOverride = copier.copy(member) as IrDeclaration
        require(deepCopyFakeOverride is IrProperty || deepCopyFakeOverride is IrSimpleFunction)
        deepCopyFakeOverride.parent = clazz
        require(deepCopyFakeOverride is IrSymbolOwner)
        assert(deepCopyFakeOverride.symbol.owner == deepCopyFakeOverride)
        assert((deepCopyFakeOverride.symbol.descriptor as? WrappedDeclarationDescriptor<*>)?.owner == deepCopyFakeOverride)

        return deepCopyFakeOverride
    }


    private val IrDeclaration.modality get() = when(this) {
        is IrProperty -> this.modality
        is IrSimpleFunction -> this.modality
        else -> error("Could not take modality of ${this.render()}")
    }

    // TODO: make me non-recursive.
    fun buildFakeOverridesForClass(clazz: IrClass) {
        if (haveFakeOverrides.contains(clazz)) return
        if ((clazz.symbol as? IrClassPublicSymbolImpl)?.isPublicApi != true) return

        val superTypes = clazz.superTypes

        val superClasses = superTypes.map {
            it.classifierOrNull?.owner as IrClass?
        }.filterNotNull()

        superClasses.forEach {
            buildFakeOverridesForClass(it)
            haveFakeOverrides.add(it)
        }

        debug("\n\nBUILDING fake overrides for ${clazz.render()}:")

        val existingMembers = clazz.declarations
            .filter{ it is IrProperty || it is IrSimpleFunction }
            .filter { (it as IrSymbolOwner).symbol.isPublicApi }

        val propertyOverriddenSymbols = mutableMapOf<IrProperty, MutableList<IrPropertySymbol>>()
        val functionOverriddenSymbols = mutableMapOf<IrSimpleFunction, MutableList<IrSimpleFunctionSymbol>>()

        val overrideCandidates = superTypes.flatMap { superType ->
            val superClass = superType.classifierOrNull?.owner as IrClass? // TODO: What if there's no class?
            val overriddenMembers = superClass!!.declarations
                .filter { it is IrSimpleFunction || it is IrProperty }
                .filter { (it as IrSymbolOwner).symbol.isPublicApi }
            val overrides = overriddenMembers.map { overridden ->
                val override = fakeOverrideMember(superType, overridden, clazz)
                when (override) {
                    is IrSimpleFunction -> {
                        functionOverriddenSymbols.put(override, mutableListOf((overridden as IrSimpleFunction).symbol))
                    }
                    is IrProperty -> {
                        propertyOverriddenSymbols.put(override, mutableListOf((overridden as IrProperty).symbol))
                    }
                    else -> error("Unexpected ")
                }
                override
            }
            overrides
        }

        val mergedOverrideCandidates = arrayListOf<IrDeclaration>()

        fun MutableList<IrDeclaration>.updateMergedCandidatesWith(candidate: IrDeclaration) {
            // TODO: this is a square asymptotics.
            this.forEachIndexed { index, existingCandidate ->

                if (existingCandidate.overrides(candidate, considerModality = true)) {
                    when (existingCandidate) {
                        is IrSimpleFunction -> {
                            functionOverriddenSymbols[existingCandidate]!!.addAll(functionOverriddenSymbols[candidate]!!)
                        }
                        is IrProperty -> {
                            propertyOverriddenSymbols[existingCandidate]!!.addAll(propertyOverriddenSymbols[candidate]!!)
                        }
                    }
                    return
                }
                if (candidate.overrides(existingCandidate, considerModality = true)) {
                    val oldOverrideCandidate = mergedOverrideCandidates[index]
                    mergedOverrideCandidates[index] = candidate
                    when (candidate) {
                        is IrSimpleFunction -> {
                            functionOverriddenSymbols[candidate]!!.addAll(functionOverriddenSymbols[oldOverrideCandidate]!!)
                        }
                        is IrProperty -> {
                            propertyOverriddenSymbols[candidate]!!.addAll(propertyOverriddenSymbols[oldOverrideCandidate]!!)
                        }
                    }
                    return
                }
            }

            this.add(candidate)
        }

        for(candidate in overrideCandidates) {
            if (existingMembers.any { it.overrides(candidate) } ) {
                continue
            }
            mergedOverrideCandidates.updateMergedCandidatesWith(candidate)
        }

        debug("\nDESERIALIZED members:\n\t${existingMembers.map {it.render()}.joinToString("\n\t")}")

        val fakeOverrides = mergedOverrideCandidates
            .map { singleFakeOverride ->
                when (singleFakeOverride) {
                    is IrFunctionImpl -> {
                        singleFakeOverride.overriddenSymbols = functionOverriddenSymbols[singleFakeOverride]!!
                    }
                    is IrProperty -> {
                        val overriddenProperties = propertyOverriddenSymbols[singleFakeOverride]!!
                        val overriddenGetters = overriddenProperties.map  { it.owner.getter?.symbol }.filterNotNull()
                        val overriddenSetters = overriddenProperties.map  { it.owner.setter?.symbol }.filterNotNull()
                        (singleFakeOverride.getter as? IrFunctionImpl?)?.let { it.overriddenSymbols = overriddenGetters }
                        (singleFakeOverride.setter as? IrFunctionImpl?)?.let { it.overriddenSymbols = overriddenSetters }
                    }
                    else -> error("No overridden symbols for ${ir2string(singleFakeOverride)}")
                }
                singleFakeOverride
            }

        debug("SYNTHESIZED members:\n\t${fakeOverrides.map {it.render()}.joinToString("\n\t")}}")

        fun redelegateFunction(fake: IrSimpleFunction) {
            val properSignature = signaturer.composePublicIdSignature(fake)

            val deserializedSymbol =
                symbolTable.referenceSimpleFunctionFromLinker(WrappedSimpleFunctionDescriptor(), properSignature)

            (fake.symbol as? IrDelegatingSimpleFunctionSymbolImpl)?.let {
                debug("REDELEGATING ${fake.nameForIrSerialization} to $deserializedSymbol")
                it.delegate = deserializedSymbol
            } ?: error("Expected a delegating symbol in ${clazz.render()} ${fake.render()} ${fake.symbol}")

            deserializedSymbol.bind(fake)
            symbolTable.rebindSimpleFunction(properSignature, fake)

            (deserializedSymbol.descriptor as? WrappedSimpleFunctionDescriptor)?.let {
                if (!it.isBound()) it.bind(fake)
            }
        }

        fun redelegateProperty(fake: IrProperty) {
            val properSignature = signaturer.composePublicIdSignature(fake)

            val deserializedSymbol =
                symbolTable.referencePropertyFromLinker(WrappedPropertyDescriptor(), properSignature)

            (fake.symbol as? IrDelegatingPropertySymbolImpl)?.let {
                debug("REDELEGATING ${fake.nameForIrSerialization} to $deserializedSymbol")
                it.delegate = deserializedSymbol
            } ?: error("Expected a delegating symbol in ${ir2string(clazz)} ${ir2string(fake)} ${fake.symbol}")

            deserializedSymbol.bind(fake)
            symbolTable.rebindProperty(properSignature, fake)

            (deserializedSymbol.descriptor as? WrappedPropertyDescriptor)?.let {
                if (!it.isBound()) it.bind(fake)
            }

            fake.getter?.let { redelegateFunction(it) }
            fake.setter?.let { redelegateFunction(it) }
        }

        fakeOverrides.forEach { fake ->
            when (fake) {
                is IrSimpleFunction -> {
                    redelegateFunction(fake)
                    checkOverriddenSymbols(fake)
                }
                is IrProperty -> redelegateProperty( fake)
            }
            clazz.declarations.add(fake)
        }
    }

    fun checkOverriddenSymbols(fake: IrSimpleFunction) {
        fake.overriddenSymbols.forEach { symbol ->
            if(!(symbol.owner.parent as IrClass).declarations.contains(symbol.owner)) {
                debug("CHECK overridden symbols: ${fake.render()} refers to ${symbol.owner.render()} which is not a member of ${symbol.owner.parent.render()}")
            }
        }
    }

    fun IrDeclaration.overrides(other: IrDeclaration, considerModality: Boolean = false): Boolean {
        when (this) {
            is IrSimpleFunction -> {
                if (other !is IrSimpleFunction) return false
                if (this.name != other.name) return false
                // TODO: do we need to match type parameter bounds?
                if (this.typeParameters.size != other.typeParameters.size) return false

                val typeCheckerContext = IrTypeCheckerContextWithAdditionalAxioms(irBuiltIns, this.typeParameters, other.typeParameters)
                if (this.valueParameters.size != other.valueParameters.size) return false
                this.valueParameters.forEachIndexed { index, parameter ->
                    if (!AbstractTypeChecker.equalTypes(typeCheckerContext as AbstractTypeCheckerContext, other.valueParameters[index].type, parameter.type)) return false
                }
                if (!AbstractTypeChecker.isSubtypeOf(typeCheckerContext as AbstractTypeCheckerContext, this.returnType, other.returnType)) return false
                if (considerModality && this.modality == Modality.ABSTRACT && other.modality != Modality.ABSTRACT) return false

                return true
            }
            is IrProperty -> {
                if (other !is IrProperty) return false
                if (this.name != other.name) return false
                // TODO: We assume getter always exists for a property here.
                if (!this.getter!!.returnType.isSubtypeOf(other.getter!!.returnType, irBuiltIns)) return false

                return true
            }
            else -> return false
        }
    }

    // TODO: turn filter into a method and make fo builder abstract
    fun provideFakeOverrides(module: IrModuleFragment, filter: (IrClass) -> Boolean) {
        module.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }
            override fun visitClass(declaration: IrClass) {

                if (filter(declaration) && declaration.symbol.isPublicApi) {
                    buildFakeOverridesForClass(declaration)
                    haveFakeOverrides.add(declaration)
                }
                super.visitClass(declaration)
            }
            override fun visitFunction(declaration: IrFunction) {
                // Don't go for local classes
                return
            }
        })

    }
}

private fun IrType.render(): String = RenderIrElementVisitor().renderType(this)

