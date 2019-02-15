/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java

import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirAnnotationContainer
import org.jetbrains.kotlin.fir.expressions.resolvedFqName
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.load.java.AnnotationTypeQualifierResolver
import org.jetbrains.kotlin.load.java.MUTABLE_ANNOTATIONS
import org.jetbrains.kotlin.load.java.READ_ONLY_ANNOTATIONS
import org.jetbrains.kotlin.load.java.typeEnhancement.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.utils.Jsr305State
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

internal class EnhancementSignatureParts(
    private val typeQualifierResolver: FirAnnotationTypeQualifierResolver,
    private val typeContainer: FirAnnotationContainer?,
    private val current: FirResolvedTypeRef,
    private val fromOverridden: Collection<FirResolvedTypeRef>,
    private val isCovariant: Boolean,
    private val context: FirJavaEnhancementContext,
    private val containerApplicabilityType: AnnotationTypeQualifierResolver.QualifierApplicabilityType,
    private val jsr305State: Jsr305State
) {
    private val isForVarargParameter get() = typeContainer.safeAs<FirValueParameter>()?.isVararg == true

    private fun ConeKotlinType.toFqNameUnsafe(): FqNameUnsafe? =
        ((this as? ConeSymbolBasedType)?.symbol as? ConeClassLikeSymbol)?.classId?.asSingleFqName()?.toUnsafe()

    // TODO
    //private fun FirResolvedTypeRef.unwrapEnhancement(): FirResolvedTypeRef = this

    internal fun enhance(
        signatureEnhancement: FirSignatureEnhancement,
        predefined: TypeEnhancementInfo? = null
    ): FirSignatureEnhancement.PartEnhancementResult {
        val qualifiers = computeIndexedQualifiersForOverride(signatureEnhancement)

        val qualifiersWithPredefined: ((Int) -> JavaTypeQualifiers)? = predefined?.let {
            { index ->
                predefined.map[index] ?: qualifiers(index)
            }
        }

        val containsFunctionN = current.type.contains {
            val classId = it.symbol.classId
            classId.shortClassName == JavaToKotlinClassMap.FUNCTION_N_FQ_NAME.shortName() &&
                    classId.asSingleFqName() == JavaToKotlinClassMap.FUNCTION_N_FQ_NAME
        }

        val enhancedCurrent = current.enhance(qualifiersWithPredefined ?: qualifiers)
        return if (enhancedCurrent !== current)
            FirSignatureEnhancement.PartEnhancementResult(enhancedCurrent, wereChanges = true, containsFunctionN = containsFunctionN)
        else
            FirSignatureEnhancement.PartEnhancementResult(current, wereChanges = false, containsFunctionN = containsFunctionN)
    }

    private fun ConeKotlinType.contains(isSpecialType: (ConeClassLikeType) -> Boolean): Boolean {
        return when (this) {
            is ConeClassLikeType -> isSpecialType(this)
            else -> false
        }
    }


    private fun FirResolvedTypeRef.toIndexed(
        typeQualifierResolver: FirAnnotationTypeQualifierResolver,
        signatureEnhancement: FirSignatureEnhancement,
        context: FirJavaEnhancementContext
    ): List<TypeAndDefaultQualifiers> {
        val list = ArrayList<TypeAndDefaultQualifiers>(1)

        fun add(type: FirResolvedTypeRef) {
            val c = context.copyWithNewDefaultTypeQualifiers(typeQualifierResolver, signatureEnhancement, type.annotations)

            list.add(
                TypeAndDefaultQualifiers(
                    type,
                    c.defaultTypeQualifiers
                        ?.get(AnnotationTypeQualifierResolver.QualifierApplicabilityType.TYPE_USE)
                )
            )

            for (arg in type.typeArguments()) {
                if (arg is FirStarProjection) {
                    // TODO: wildcards
                    // TODO: sort out how to handle wildcards
                    //list.add(TypeAndDefaultQualifiers(arg.type))
                } else if (arg is FirTypeProjectionWithVariance) {
                    add(arg.typeRef as FirResolvedTypeRef)
                }
            }
        }

        add(this)
        return list
    }

    private fun ConeKotlinType.extractQualifiers(): JavaTypeQualifiers {
        val (lower, upper) =
            if (this is ConeFlexibleType) {
                Pair(this.lowerBound, this.upperBound)
            } else {
                Pair(this, this)
            }

        val mapping = JavaToKotlinClassMap
        return JavaTypeQualifiers(
            when {
                lower.isMarkedNullable -> NullabilityQualifier.NULLABLE
                !upper.isMarkedNullable -> NullabilityQualifier.NOT_NULL
                else -> null
            },
            when {
                mapping.isReadOnly(lower.toFqNameUnsafe()) -> MutabilityQualifier.READ_ONLY
                mapping.isMutable(upper.toFqNameUnsafe()) -> MutabilityQualifier.MUTABLE
                else -> null
            },
            isNotNullTypeParameter = false //TODO: unwrap() is NotNullTypeParameter
        )
    }

    private fun composeAnnotations(first: List<FirAnnotationCall>, second: List<FirAnnotationCall>): List<FirAnnotationCall> {
        return when {
            first.isEmpty() -> second
            second.isEmpty() -> first
            else -> first + second
        }
    }

    private fun FirResolvedTypeRef.extractQualifiersFromAnnotations(
        isHeadTypeConstructor: Boolean,
        defaultQualifiersForType: JavaTypeQualifiers?,
        signatureEnhancement: FirSignatureEnhancement
    ): JavaTypeQualifiers {
        val composedAnnotation =
            if (isHeadTypeConstructor && typeContainer != null)
                composeAnnotations(typeContainer.annotations, annotations)
            else
                annotations

        fun <T : Any> List<FqName>.ifPresent(qualifier: T) =
            if (any { fqName ->
                    composedAnnotation.any { it.resolvedFqName == fqName }
                }
            ) qualifier else null

        fun <T : Any> uniqueNotNull(x: T?, y: T?) = if (x == null || y == null || x == y) x ?: y else null

        val defaultTypeQualifier =
            if (isHeadTypeConstructor)
                context.defaultTypeQualifiers?.get(containerApplicabilityType)
            else
                defaultQualifiersForType

        val nullabilityInfo = with(signatureEnhancement) {
            composedAnnotation.extractNullability(typeQualifierResolver)
                ?: defaultTypeQualifier?.nullability?.let { nullability ->
                    NullabilityQualifierWithMigrationStatus(
                        nullability,
                        defaultTypeQualifier.isNullabilityQualifierForWarning
                    )
                }
        }

        @Suppress("SimplifyBooleanWithConstants")
        return JavaTypeQualifiers(
            nullabilityInfo?.qualifier,
            uniqueNotNull(
                READ_ONLY_ANNOTATIONS.ifPresent(
                    MutabilityQualifier.READ_ONLY
                ),
                MUTABLE_ANNOTATIONS.ifPresent(
                    MutabilityQualifier.MUTABLE
                )
            ),
            isNotNullTypeParameter = nullabilityInfo?.qualifier == NullabilityQualifier.NOT_NULL && true, /* TODO: isTypeParameter()*/
            isNullabilityQualifierForWarning = nullabilityInfo?.isForWarningOnly == true
        )
    }

    private fun FirResolvedTypeRef.computeQualifiersForOverride(
        fromSupertypes: Collection<ConeKotlinType>,
        defaultQualifiersForType: JavaTypeQualifiers?,
        isHeadTypeConstructor: Boolean
    ): JavaTypeQualifiers {
        val superQualifiers = fromSupertypes.map { it.extractQualifiers() }
        val mutabilityFromSupertypes = superQualifiers.mapNotNull { it.mutability }.toSet()
        val nullabilityFromSupertypes = superQualifiers.mapNotNull { it.nullability }.toSet()
        val nullabilityFromSupertypesWithWarning = fromOverridden
            .mapNotNull { it.type.extractQualifiers().nullability }
            .toSet()

        val own = extractQualifiersFromAnnotations(isHeadTypeConstructor, defaultQualifiersForType, FirSignatureEnhancement(jsr305State))
        val ownNullability = own.takeIf { !it.isNullabilityQualifierForWarning }?.nullability
        val ownNullabilityForWarning = own.nullability

        val isCovariantPosition = isCovariant && isHeadTypeConstructor
        val nullability =
            nullabilityFromSupertypes.select(ownNullability, isCovariantPosition)
                // Vararg value parameters effectively have non-nullable type in Kotlin
                // and having nullable types in Java may lead to impossibility of overriding them in Kotlin
                ?.takeUnless { isForVarargParameter && isHeadTypeConstructor && it == NullabilityQualifier.NULLABLE }

        val mutability =
            mutabilityFromSupertypes
                .select(MutabilityQualifier.MUTABLE, MutabilityQualifier.READ_ONLY, own.mutability, isCovariantPosition)

        val canChange = ownNullabilityForWarning != ownNullability || nullabilityFromSupertypesWithWarning != nullabilityFromSupertypes
        val isAnyNonNullTypeParameter = own.isNotNullTypeParameter || superQualifiers.any { it.isNotNullTypeParameter }
        if (nullability == null && canChange) {
            val nullabilityWithWarning =
                nullabilityFromSupertypesWithWarning.select(ownNullabilityForWarning, isCovariantPosition)

            return createJavaTypeQualifiers(
                nullabilityWithWarning, mutability,
                forWarning = true, isAnyNonNullTypeParameter = isAnyNonNullTypeParameter
            )
        }

        return createJavaTypeQualifiers(
            nullability, mutability,
            forWarning = nullability == null,
            isAnyNonNullTypeParameter = isAnyNonNullTypeParameter
        )
    }

    private fun computeIndexedQualifiersForOverride(
        signatureEnhancement: FirSignatureEnhancement
    ): (Int) -> JavaTypeQualifiers {
        val indexedFromSupertypes = fromOverridden.map { it.toIndexed(typeQualifierResolver, signatureEnhancement, context) }
        val indexedThisType = current.toIndexed(typeQualifierResolver, signatureEnhancement, context)

        // The covariant case may be hard, e.g. in the superclass the return may be Super<T>, but in the subclass it may be Derived, which
        // is declared to extend Super<T>, and propagating data here is highly non-trivial, so we only look at the head type constructor
        // (outermost type), unless the type in the subclass is interchangeable with the all the types in superclasses:
        // e.g. we have (Mutable)List<String!>! in the subclass and { List<String!>, (Mutable)List<String>! } from superclasses
        // Note that `this` is flexible here, so it's equal to it's bounds
        val onlyHeadTypeConstructor = isCovariant && fromOverridden.any { true /*equalTypes(it, this)*/ }

        val treeSize = if (onlyHeadTypeConstructor) 1 else indexedThisType.size
        val computedResult = Array(treeSize) { index ->
            val isHeadTypeConstructor = index == 0
            assert(isHeadTypeConstructor || !onlyHeadTypeConstructor) { "Only head type constructors should be computed" }

            val (qualifiers, defaultQualifiers) = indexedThisType[index]
            val verticalSlice = indexedFromSupertypes.mapNotNull { it.getOrNull(index)?.type?.type }

            // Only the head type constructor is safely co-variant
            qualifiers.computeQualifiersForOverride(verticalSlice, defaultQualifiers, isHeadTypeConstructor)
        }

        return { index -> computedResult.getOrElse(index) { JavaTypeQualifiers.NONE } }
    }
}

