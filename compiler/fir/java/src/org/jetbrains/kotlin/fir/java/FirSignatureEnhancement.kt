/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java

import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.impl.FirMemberFunctionImpl
import org.jetbrains.kotlin.fir.declarations.impl.FirMemberPropertyImpl
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirAnnotationContainer
import org.jetbrains.kotlin.fir.expressions.resolvedFqName
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeSymbol
import org.jetbrains.kotlin.fir.types.ConeSymbolBasedType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.load.java.*
import org.jetbrains.kotlin.load.java.descriptors.NullDefaultValue
import org.jetbrains.kotlin.load.java.descriptors.StringDefaultValue
import org.jetbrains.kotlin.load.java.typeEnhancement.NullabilityQualifier
import org.jetbrains.kotlin.load.java.typeEnhancement.NullabilityQualifierWithMigrationStatus
import org.jetbrains.kotlin.load.java.typeEnhancement.PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE
import org.jetbrains.kotlin.load.kotlin.SignatureBuildingComponents
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.Jsr305State
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class FirSignatureEnhancement(private val jsr305State: Jsr305State) {

    private fun FirAnnotationCall.extractNullabilityTypeFromArgument(): NullabilityQualifierWithMigrationStatus? {
        val enumValue = this.arguments.firstOrNull()?.toResolvedCallableSymbol()?.callableId?.callableName
        // if no argument is specified, use default value: NOT_NULL
            ?: return NullabilityQualifierWithMigrationStatus(NullabilityQualifier.NOT_NULL)

        return when (enumValue.asString()) {
            "ALWAYS" -> NullabilityQualifierWithMigrationStatus(NullabilityQualifier.NOT_NULL)
            "MAYBE", "NEVER" -> NullabilityQualifierWithMigrationStatus(NullabilityQualifier.NULLABLE)
            "UNKNOWN" -> NullabilityQualifierWithMigrationStatus(NullabilityQualifier.FORCE_FLEXIBILITY)
            else -> null
        }
    }

    fun List<FirAnnotationCall>.extractNullability(
        annotationTypeQualifierResolver: FirAnnotationTypeQualifierResolver
    ): NullabilityQualifierWithMigrationStatus? =
        this.firstNotNullResult { annotationCall ->
            this@FirSignatureEnhancement.extractNullability(
                annotationTypeQualifierResolver,
                annotationCall
            )
        }


    fun extractNullability(
        annotationTypeQualifierResolver: FirAnnotationTypeQualifierResolver,
        annotationCall: FirAnnotationCall
    ): NullabilityQualifierWithMigrationStatus? {
        extractNullabilityFromKnownAnnotations(annotationCall)?.let { return it }

        val typeQualifierAnnotation =
            annotationTypeQualifierResolver.resolveTypeQualifierAnnotation(annotationCall)
                ?: return null

        val jsr305State = annotationTypeQualifierResolver.resolveJsr305AnnotationState(annotationCall)
        if (jsr305State.isIgnore) return null

        return extractNullabilityFromKnownAnnotations(typeQualifierAnnotation)?.copy(isForWarningOnly = jsr305State.isWarning)
    }

    private fun extractNullabilityFromKnownAnnotations(
        annotationCall: FirAnnotationCall
    ): NullabilityQualifierWithMigrationStatus? {
        val annotationFqName = annotationCall.resolvedFqName ?: return null

        return when {
            annotationFqName in NULLABLE_ANNOTATIONS -> NullabilityQualifierWithMigrationStatus(NullabilityQualifier.NULLABLE)
            annotationFqName in NOT_NULL_ANNOTATIONS -> NullabilityQualifierWithMigrationStatus(NullabilityQualifier.NOT_NULL)
            annotationFqName == JAVAX_NONNULL_ANNOTATION -> annotationCall.extractNullabilityTypeFromArgument()

            annotationFqName == COMPATQUAL_NULLABLE_ANNOTATION && jsr305State.enableCompatqualCheckerFrameworkAnnotations ->
                NullabilityQualifierWithMigrationStatus(NullabilityQualifier.NULLABLE)

            annotationFqName == COMPATQUAL_NONNULL_ANNOTATION && jsr305State.enableCompatqualCheckerFrameworkAnnotations ->
                NullabilityQualifierWithMigrationStatus(NullabilityQualifier.NOT_NULL)

            annotationFqName == ANDROIDX_RECENTLY_NON_NULL_ANNOTATION -> NullabilityQualifierWithMigrationStatus(
                NullabilityQualifier.NOT_NULL,
                isForWarningOnly = true
            )

            annotationFqName == ANDROIDX_RECENTLY_NULLABLE_ANNOTATION -> NullabilityQualifierWithMigrationStatus(
                NullabilityQualifier.NULLABLE,
                isForWarningOnly = true
            )
            else -> null
        }
    }

    private val FirTypedDeclaration.valueParameters: List<FirValueParameter> get() = (this as? FirFunction)?.valueParameters.orEmpty()

    @Suppress("unused")
    fun FirCallableMember.enhanceSignature(
        container: FirRegularClass,
        typeQualifierResolver: FirAnnotationTypeQualifierResolver,
        context: FirJavaEnhancementContext
    ): FirCallableMember {
        // TODO: Fake overrides with one overridden has been enhanced before
        //if (kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE && original.overriddenDescriptors.size == 1) return this

        val memberContext = context.copyWithNewDefaultTypeQualifiers(typeQualifierResolver, this@FirSignatureEnhancement, annotations)

        // When loading method as an override for a property, all annotations are stick to its getter
        val annotationOwnerForMember =
            if (this is FirProperty && getter !is FirDefaultPropertyAccessor)
                getter
            else
                this

        val receiverTypeEnhancement =
            if (this.receiverTypeRef != null)
                partsForValueParameter(
                    typeQualifierResolver,
                    parameterContainer = annotationOwnerForMember.safeAs<FirNamedFunction>()?.receiverTypeRef,
                    methodContext = memberContext
                ) { it.receiverTypeRef as FirResolvedTypeRef }.enhance(this@FirSignatureEnhancement)
            else null


        val predefinedEnhancementInfo =
            (this as? FirNamedFunction)
                ?.run { SignatureBuildingComponents.signature(container.symbol.classId, this.computeJvmDescriptor()) }
                ?.let { signature -> PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE[signature] }

        predefinedEnhancementInfo?.let {
            assert(this is FirNamedFunction && it.parametersInfo.size == valueParameters.size) {
                "Predefined enhancement info for $this has ${it.parametersInfo.size}, but ${valueParameters.size} expected"
            }
        }

        val valueParameterEnhancements = annotationOwnerForMember.valueParameters.mapIndexed { index, p ->
            val enhancementResult = partsForValueParameter(typeQualifierResolver, p, memberContext) {
                it.valueParameters[index].returnTypeRef as FirResolvedTypeRef
            }.enhance(this@FirSignatureEnhancement, predefinedEnhancementInfo?.parametersInfo?.getOrNull(index))

            val actualType = (if (enhancementResult.wereChanges) enhancementResult.type else p.returnTypeRef) as FirResolvedTypeRef
            val hasDefaultValue = p.hasDefaultValueInAnnotation(actualType)
            val wereChanges = enhancementResult.wereChanges || (hasDefaultValue != (p.defaultValue != null))

            ValueParameterEnhancementResult(enhancementResult.type, hasDefaultValue, wereChanges, enhancementResult.containsFunctionN)
        }

        @Suppress("ConstantConditionIf")
        val returnTypeEnhancement =
            parts(
                typeQualifierResolver,
                typeContainer = annotationOwnerForMember, isCovariant = true,
                containerContext = memberContext,
                containerApplicabilityType =
                if (false) // TODO: this.safeAs<FirProperty>()?.isJavaField == true
                    AnnotationTypeQualifierResolver.QualifierApplicabilityType.FIELD
                else
                    AnnotationTypeQualifierResolver.QualifierApplicabilityType.METHOD_RETURN_TYPE
            ) { it.returnTypeRef as FirResolvedTypeRef }.enhance(this@FirSignatureEnhancement, predefinedEnhancementInfo?.returnTypeInfo)

        val containsFunctionN = receiverTypeEnhancement?.containsFunctionN == true || returnTypeEnhancement.containsFunctionN ||
                valueParameterEnhancements.any { it.containsFunctionN }

        if ((receiverTypeEnhancement?.wereChanges == true)
            || returnTypeEnhancement.wereChanges || valueParameterEnhancements.any { it.wereChanges } || containsFunctionN
        ) {
            // TODO
            //val additionalUserData = if (containsFunctionN) DEPRECATED_FUNCTION_KEY to DeprecationCausedByFunctionN(this) else null
            when (this) {
                is FirNamedFunction -> enhance(
                    receiverTypeEnhancement?.type,
                    valueParameterEnhancements.map { ValueParameterData(it.type, it.hasDefaultValue) },
                    returnTypeEnhancement.type
                )
                is FirProperty -> enhance(
                    receiverTypeEnhancement?.type,
                    returnTypeEnhancement.type
                )
            }
        }

        return this
    }

    private fun FirFunction.computeJvmDescriptor(): String = buildString {
        append(
            when (this@computeJvmDescriptor) {
                is FirConstructor -> "<init>"
                is FirNamedFunction -> name.asString()
                else -> error("Strange function for JVM descriptor: ${this@computeJvmDescriptor::class.java}")
            }
        )

        append("(")
        for (parameter in valueParameters) {
            // TODO: appendErasedType(parameter.returnTypeRef)
        }
        append(")")

        if (this@computeJvmDescriptor !is FirNamedFunction || (returnTypeRef as FirResolvedTypeRef).isUnit()) {
            append("V")
        } else {
            // TODO: appendErasedType(returnTypeRef)
        }
    }

    private fun FirResolvedTypeRef.isUnit(): Boolean {
        val classId = ((type as? ConeSymbolBasedType)?.symbol as? ConeClassLikeSymbol)?.classId ?: return false
        return classId.packageFqName == FqName("kotlin") && classId.relativeClassName == FqName("Unit")
    }

    @Suppress("UNUSED_PARAMETER")
    private class ValueParameterData(type: FirResolvedTypeRef, hasDefaultValue: Boolean)

    // TODO: rewrite to transformer
    @Suppress("UNUSED_PARAMETER")
    private fun FirNamedFunction.enhance(
        receiverTypeRef: FirResolvedTypeRef?,
        valueParameterTypeRefs: List<ValueParameterData>,
        returnTypeRef: FirResolvedTypeRef
    ): FirNamedFunction {
        return (this as FirMemberFunctionImpl).apply {
            this.receiverTypeRef = receiverTypeRef
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun FirProperty.enhance(
        receiverTypeRef: FirResolvedTypeRef?,
        returnTypeRef: FirResolvedTypeRef
    ): FirProperty {
        return (this as FirMemberPropertyImpl).apply {
            this.receiverTypeRef = receiverTypeRef
        }
    }

    private fun FirCallableMember.partsForValueParameter(
        typeQualifierResolver: FirAnnotationTypeQualifierResolver,
        // TODO: investigate if it's really can be a null (check properties' with extension overrides in Java)
        parameterContainer: FirAnnotationContainer?,
        methodContext: FirJavaEnhancementContext,
        collector: (FirCallableMember) -> FirResolvedTypeRef
    ) = parts(
        typeQualifierResolver,
        parameterContainer, false,
        parameterContainer?.let {
            methodContext.copyWithNewDefaultTypeQualifiers(typeQualifierResolver, this@FirSignatureEnhancement, it.annotations)
        } ?: methodContext,
        AnnotationTypeQualifierResolver.QualifierApplicabilityType.VALUE_PARAMETER,
        collector
    )

    private fun FirCallableMember.parts(
        typeQualifierResolver: FirAnnotationTypeQualifierResolver,
        typeContainer: FirAnnotationContainer?,
        isCovariant: Boolean,
        containerContext: FirJavaEnhancementContext,
        containerApplicabilityType: AnnotationTypeQualifierResolver.QualifierApplicabilityType,
        collector: (FirCallableMember) -> FirResolvedTypeRef
    ): EnhancementSignatureParts {
        return EnhancementSignatureParts(
            typeQualifierResolver,
            typeContainer,
            collector(this),
            emptyList(), // TODO: overridden descriptors
//            this.overriddenDescriptors.map {
//                collector(it)
//            },
            isCovariant,
            // recompute default type qualifiers using type annotations
            containerContext.copyWithNewDefaultTypeQualifiers(
                typeQualifierResolver, this@FirSignatureEnhancement, collector(this).annotations
            ),
            containerApplicabilityType,
            jsr305State
        )
    }

    private fun FirValueParameter.hasDefaultValueInAnnotation(@Suppress("UNUSED_PARAMETER") type: FirResolvedTypeRef): Boolean {
        val defaultValue = getDefaultValueFromAnnotation()

        return when (defaultValue) {
            is StringDefaultValue -> true // TODO: type.lexicalCastFrom(defaultValue.value) != null
            NullDefaultValue -> (returnTypeRef as FirResolvedTypeRef).type.isMarkedNullable // TODO: TypeUtils.acceptsNullable(type)
            null -> this.defaultValue != null
        }// TODO: && overriddenDescriptors.isEmpty()
    }

    internal open class PartEnhancementResult(
        val type: FirResolvedTypeRef,
        val wereChanges: Boolean,
        val containsFunctionN: Boolean
    )

    internal class ValueParameterEnhancementResult(
        type: FirResolvedTypeRef,
        val hasDefaultValue: Boolean,
        wereChanges: Boolean,
        containsFunctionN: Boolean
    ) : PartEnhancementResult(type, wereChanges, containsFunctionN)

}