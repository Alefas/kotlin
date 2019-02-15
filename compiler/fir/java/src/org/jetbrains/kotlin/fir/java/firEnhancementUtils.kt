/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java

import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirConstExpression
import org.jetbrains.kotlin.fir.expressions.resolvedFqName
import org.jetbrains.kotlin.fir.resolve.constructType
import org.jetbrains.kotlin.fir.resolve.toTypeProjection
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.FirResolvedTypeRefImpl
import org.jetbrains.kotlin.load.java.JvmAnnotationNames
import org.jetbrains.kotlin.load.java.descriptors.AnnotationDefaultValue
import org.jetbrains.kotlin.load.java.descriptors.NullDefaultValue
import org.jetbrains.kotlin.load.java.descriptors.StringDefaultValue
import org.jetbrains.kotlin.load.java.structure.JavaType
import org.jetbrains.kotlin.load.java.typeEnhancement.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

internal fun FirResolvedTypeRef.enhance(
    qualifiers: (Int) -> JavaTypeQualifiers
): FirResolvedTypeRef {
    return enhancePossiblyFlexible(qualifiers, 0).type
}

private class EnhanceTypeResult<T>(val type: T, val subtreeSize: Int, val wereChanges: Boolean) {
    constructor(type: T) : this(type, 1, false)
}

private fun <T> T.unchanged(): EnhanceTypeResult<T> = EnhanceTypeResult(this)

// The index in the lambda is the position of the type component:
// Example: for `A<B, C<D, E>>`, indices go as follows: `0 - A<...>, 1 - B, 2 - C<D, E>, 3 - D, 4 - E`,
// which corresponds to the left-to-right breadth-first walk of the tree representation of the type.
// For flexible types, both bounds are indexed in the same way: `(A<B>..C<D>)` gives `0 - (A<B>..C<D>), 1 - B and D`.
private fun FirResolvedTypeRef.enhancePossiblyFlexible(
    qualifiers: (Int) -> JavaTypeQualifiers,
    index: Int
): EnhanceTypeResult<FirResolvedTypeRef> {
    val type = type
    if (type is ConeKotlinErrorType || type is ConeClassErrorType) return this.unchanged()
    val arguments = typeArguments()
    return when (type) {
        is ConeFlexibleType -> {
            val lowerBound = type.lowerBound
            val lowerResult = lowerBound.enhanceInflexibleType(arguments, TypeComponentPosition.FLEXIBLE_LOWER, qualifiers, index)
            val upperBound = type.upperBound
            val upperResult = upperBound.enhanceInflexibleType(arguments, TypeComponentPosition.FLEXIBLE_UPPER, qualifiers, index)
            assert(lowerResult.subtreeSize == upperResult.subtreeSize) {
                "Different tree sizes of bounds: " +
                        "lower = ($lowerBound, ${lowerResult.subtreeSize}), " +
                        "upper = ($upperBound, ${upperResult.subtreeSize})"
            }
            val wereChanges = lowerResult.wereChanges || upperResult.wereChanges

            if (!wereChanges) {
                this.unchanged()
            } else {
                EnhanceTypeResult(
                    FirResolvedTypeRefImpl(
                        session, psi,
                        ConeFlexibleType(lowerResult.type, upperResult.type),
                        isMarkedNullable, annotations
                    ), lowerResult.subtreeSize, true
                )
            }
        }
        else -> {
            val enhanced = type.enhanceInflexibleType(arguments, TypeComponentPosition.INFLEXIBLE, qualifiers, index)
            if (!enhanced.wereChanges) {
                this.unchanged()
            } else {
                EnhanceTypeResult(
                    FirResolvedTypeRefImpl(session, psi, enhanced.type, isMarkedNullable, annotations),
                    enhanced.subtreeSize, true
                )
            }
        }
    }
}

private fun ConeKotlinType.enhanceInflexibleType(
    arguments: List<FirTypeProjection>,
    position: TypeComponentPosition,
    qualifiers: (Int) -> JavaTypeQualifiers,
    index: Int
): EnhanceTypeResult<ConeKotlinType> {
    val shouldEnhance = position.shouldEnhance()
    if (!shouldEnhance && typeArguments.isEmpty()) return unchanged()

    val originalSymbol = (this as? ConeSymbolBasedType)?.symbol as? FirBasedSymbol<*> ?: return unchanged()

    val effectiveQualifiers = qualifiers(index)
    val (enhancedSymbol, mutabilityChanged) = originalSymbol.enhanceMutability(effectiveQualifiers, position)

    var globalArgIndex = index + 1
    var wereChanges = mutabilityChanged
    val enhancedArguments = arguments.mapIndexed { localArgIndex, arg ->
        if (arg is FirStarProjection) {
            globalArgIndex++
            StarProjection
            // TODO: (?) TypeUtils.makeStarProjection(enhancedClassifier.typeConstructor.parameters[localArgIndex])
        } else {
            arg as FirTypeProjectionWithVariance
            val argResolvedTypeRef = arg.typeRef as FirResolvedTypeRef
            val argEnhancedTypeRef = argResolvedTypeRef.enhancePossiblyFlexible(qualifiers, globalArgIndex)
            wereChanges = wereChanges || argEnhancedTypeRef !== argResolvedTypeRef
            globalArgIndex += argEnhancedTypeRef.subtreeSize
            argEnhancedTypeRef.type.type.toTypeProjection(arg.variance)
        }
    }

    val (enhancedNullability, _, nullabilityChanged) = getEnhancedNullability(effectiveQualifiers, position)
    wereChanges = wereChanges || nullabilityChanged

    val subtreeSize = globalArgIndex - index
    if (!wereChanges) return EnhanceTypeResult(this, subtreeSize, wereChanges = false)

    val enhancedType = enhancedSymbol.constructType(enhancedArguments.toTypedArray(), enhancedNullability)

    // TODO: why all of these is needed
//    val enhancement = if (effectiveQualifiers.isNotNullTypeParameter) NotNullTypeParameter(enhancedType) else enhancedType
//    val nullabilityForWarning = nullabilityChanged && effectiveQualifiers.isNullabilityQualifierForWarning
//    val result = if (nullabilityForWarning) wrapEnhancement(enhancement) else enhancement

    return EnhanceTypeResult(enhancedType, subtreeSize, wereChanges = true)
}

private fun ConeKotlinType.getEnhancedNullability(
    qualifiers: JavaTypeQualifiers,
    position: TypeComponentPosition
): EnhanceDetailsResult<Boolean> {
    if (!position.shouldEnhance()) return this.isMarkedNullable.noChange()

    return when (qualifiers.nullability) {
        NullabilityQualifier.NULLABLE -> true.enhancedNullability()
        NullabilityQualifier.NOT_NULL -> false.enhancedNullability()
        else -> this.isMarkedNullable.noChange()
    }
}

private data class EnhanceDetailsResult<out T>(
    val result: T,
    val mutabilityChanged: Boolean = false,
    val nullabilityChanged: Boolean = false
)
private fun <T> T.noChange() = EnhanceDetailsResult(this)
private fun <T> T.enhancedNullability() = EnhanceDetailsResult(this, nullabilityChanged = true)
private fun <T> T.enhancedMutability() = EnhanceDetailsResult(this, mutabilityChanged = true)

private fun FirBasedSymbol<*>.enhanceMutability(
    qualifiers: JavaTypeQualifiers,
    position: TypeComponentPosition
): EnhanceDetailsResult<FirBasedSymbol<*>> {
    if (!position.shouldEnhance()) return this.noChange()
    if (this !is FirClassSymbol) return this.noChange() // mutability is not applicable for type parameters
    val fqNameUnsafe = classId.asSingleFqName().toUnsafe()

    when (qualifiers.mutability) {
        MutabilityQualifier.READ_ONLY -> {
            val readOnlyFqName = JavaToKotlinClassMap.mutableToReadOnly(fqNameUnsafe)
            if (position == TypeComponentPosition.FLEXIBLE_LOWER && readOnlyFqName != null) {
                return FirClassSymbol(ClassId(classId.packageFqName, readOnlyFqName, false)).apply {
                    bind(fir)
                }.enhancedMutability()
            }
        }
        MutabilityQualifier.MUTABLE -> {
            val mutableFqName = JavaToKotlinClassMap.readOnlyToMutable(fqNameUnsafe)
            if (position == TypeComponentPosition.FLEXIBLE_UPPER && mutableFqName != null) {
                return FirClassSymbol(ClassId(classId.packageFqName, mutableFqName, false)).apply {
                    bind(fir)
                }.enhancedMutability()
            }
        }
    }

    return this.noChange()
}


internal data class TypeAndDefaultQualifiers(
    val type: FirResolvedTypeRef,
    val defaultQualifiers: JavaTypeQualifiers?
)

internal fun FirResolvedTypeRef.typeArguments(): List<FirTypeProjection> =
    (this as? FirUserTypeRef)?.qualifier?.lastOrNull()?.typeArguments.orEmpty()

fun FirValueParameter.getDefaultValueFromAnnotation(): AnnotationDefaultValue? {
    annotations.find { it.resolvedFqName == JvmAnnotationNames.DEFAULT_VALUE_FQ_NAME }
        ?.arguments?.firstOrNull()
        ?.safeAs<FirConstExpression<*>>()?.value?.safeAs<String>()
        ?.let { return StringDefaultValue(it) }

    if (annotations.any { it.resolvedFqName == JvmAnnotationNames.DEFAULT_NULL_FQ_NAME }) {
        return NullDefaultValue
    }

    return null
}

