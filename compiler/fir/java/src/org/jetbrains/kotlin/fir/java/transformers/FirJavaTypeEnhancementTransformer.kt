/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java.transformers

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirAnnotationContainer
import org.jetbrains.kotlin.fir.expressions.resolvedFqName
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.java.*
import org.jetbrains.kotlin.fir.java.declarations.FirJavaClass
import org.jetbrains.kotlin.fir.java.declarations.FirJavaMethod
import org.jetbrains.kotlin.fir.java.declarations.FirJavaValueParameter
import org.jetbrains.kotlin.fir.java.scopes.JavaClassUseSiteScope
import org.jetbrains.kotlin.fir.java.types.FirJavaTypeRef
import org.jetbrains.kotlin.fir.resolve.transformers.FirAbstractTreeTransformerWithSuperTypes
import org.jetbrains.kotlin.fir.scopes.impl.FirClassDeclaredMemberScope
import org.jetbrains.kotlin.fir.scopes.impl.FirCompositeScope
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.transformInplaceWithBeforeOperation
import org.jetbrains.kotlin.fir.transformSingle
import org.jetbrains.kotlin.fir.types.ConeClassErrorType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.impl.FirResolvedTypeRefImpl
import org.jetbrains.kotlin.fir.visitors.CompositeTransformResult
import org.jetbrains.kotlin.fir.visitors.compose
import org.jetbrains.kotlin.load.java.*
import org.jetbrains.kotlin.load.java.structure.JavaClassifierType
import org.jetbrains.kotlin.load.java.structure.JavaPrimitiveType
import org.jetbrains.kotlin.load.java.typeEnhancement.NullabilityQualifier
import org.jetbrains.kotlin.load.java.typeEnhancement.NullabilityQualifierWithMigrationStatus
import org.jetbrains.kotlin.load.java.typeEnhancement.PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE
import org.jetbrains.kotlin.load.java.typeEnhancement.PredefinedFunctionEnhancementInfo
import org.jetbrains.kotlin.load.kotlin.SignatureBuildingComponents
import org.jetbrains.kotlin.utils.Jsr305State
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult

class FirJavaTypeEnhancementTransformer(session: FirSession) : FirAbstractTreeTransformerWithSuperTypes(reversedScopePriority = true) {

    private val jsr305State: Jsr305State = Jsr305State.DEFAULT // TODO

    private val typeQualifierResolver = FirAnnotationTypeQualifierResolver(jsr305State)

    private fun FirRegularClass.buildUseSiteScope(useSiteSession: FirSession = session): JavaClassUseSiteScope {
        val superTypeScope = FirCompositeScope(mutableListOf())
        val declaredScope = FirClassDeclaredMemberScope(this, useSiteSession)
        lookupSuperTypes(this, lookupInterfaces = true, deep = false, useSiteSession = useSiteSession)
            .mapNotNullTo(superTypeScope.scopes) { useSiteSuperType ->
                if (useSiteSuperType is ConeClassErrorType) return@mapNotNullTo null
                val symbol = useSiteSuperType.symbol
                if (symbol is FirClassSymbol) {
                    symbol.fir.buildUseSiteScope(useSiteSession)
                } else {
                    null
                }
            }
        return JavaClassUseSiteScope(useSiteSession, superTypeScope, declaredScope)
    }

    private val regularClassStack = mutableListOf<FirRegularClass>()

    override fun transformRegularClass(regularClass: FirRegularClass, data: Nothing?): CompositeTransformResult<FirDeclaration> {
        if (regularClass !is FirJavaClass) return regularClass.compose()
        return withScopeCleanup {
            towerScope.scopes += regularClass.buildUseSiteScope()
            regularClassStack += regularClass
            val result = super.transformRegularClass(regularClass, data)
            regularClassStack.removeAt(regularClassStack.size - 1)
            result
        }
    }

    private val context: FirJavaEnhancementContext = FirJavaEnhancementContext(session) { null }

    private var predefinedEnhancementInfo: PredefinedFunctionEnhancementInfo? = null

    private sealed class TransformationMode {
        object Receiver : TransformationMode()

        data class ValueParameter(val index: Int) : TransformationMode()

        object ReturnType : TransformationMode()
    }

    private lateinit var mode: TransformationMode

    private lateinit var ownerFunction: FirNamedFunction

    private lateinit var memberContext: FirJavaEnhancementContext

    override fun transformNamedFunction(namedFunction: FirNamedFunction, data: Nothing?): CompositeTransformResult<FirDeclaration> {
        if (namedFunction !is FirJavaMethod) return namedFunction.compose()

        // TODO: Fake overrides with one overridden has been enhanced before
        //if (kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE && original.overriddenDescriptors.size == 1) return this

        val container = regularClassStack.last()
        memberContext = context.copyWithNewDefaultTypeQualifiers(
            typeQualifierResolver, this@FirJavaTypeEnhancementTransformer, namedFunction.annotations
        )

        // TODO: When loading method as an override for a property, all annotations are stick to its getter
//            if (this is FirProperty && getter !is FirDefaultPropertyAccessor)
//                getter
//            else
//                this

        predefinedEnhancementInfo =
            SignatureBuildingComponents.signature(container.symbol.classId, namedFunction.computeJvmDescriptor()).let { signature ->
                PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE[signature]
            }

        predefinedEnhancementInfo?.let {
            assert(it.parametersInfo.size == namedFunction.valueParameters.size) {
                "Predefined enhancement info for $this has ${it.parametersInfo.size}, but ${namedFunction.valueParameters.size} expected"
            }
        }

        ownerFunction = namedFunction
        mode = TransformationMode.Receiver
        namedFunction.receiverTypeRef = namedFunction.receiverTypeRef?.transformSingle(this, null)

        namedFunction.valueParameters.transformInplaceWithBeforeOperation(this, null) { index ->
            mode = TransformationMode.ValueParameter(index)
        }

        mode = TransformationMode.ReturnType
        namedFunction.returnTypeRef = namedFunction.returnTypeRef.transformSingle(this, null)

        return super.transformNamedFunction(namedFunction, data)
    }

    override fun transformValueParameter(valueParameter: FirValueParameter, data: Nothing?): CompositeTransformResult<FirDeclaration> {
        if (valueParameter !is FirJavaValueParameter) return valueParameter.compose()
        return super.transformValueParameter(valueParameter, data)
    }

    private fun FirTypeRef.toResolvedTypeRef(): FirResolvedTypeRef =
        when (this) {
            is FirResolvedTypeRef -> this
            is FirJavaTypeRef -> {
                val coneKotlinType = (type as JavaClassifierType).toConeKotlinType(session)
                FirResolvedTypeRefImpl(session, null, coneKotlinType, isMarkedNullable, annotations)
            }
            else -> error("Expected resolved type references in enhancement transformer: ${this::class.java}")
        }

    override fun transformTypeRef(typeRef: FirTypeRef, data: Nothing?): CompositeTransformResult<FirTypeRef> {
        if (typeRef !is FirJavaTypeRef) return typeRef.compose()

        val signatureParts = when (val mode = mode) {
            is TransformationMode.Receiver -> {
                ownerFunction.partsForValueParameter(typeQualifierResolver, typeRef, memberContext) {
                    // TODO: think which type should be here (just type ref, Java type ref, resolved type ref, cone type
                    it.receiverTypeRef!!.toResolvedTypeRef()
                }.enhance(this@FirJavaTypeEnhancementTransformer)
            }
            is TransformationMode.ValueParameter -> {
                ownerFunction.partsForValueParameter(typeQualifierResolver, typeRef, memberContext) {
                    it.valueParameters[mode.index].returnTypeRef.toResolvedTypeRef()
                }.enhance(this@FirJavaTypeEnhancementTransformer, predefinedEnhancementInfo?.parametersInfo?.getOrNull(mode.index))
            }
            is TransformationMode.ReturnType -> {
                @Suppress("ConstantConditionIf")
                ownerFunction.parts(
                    typeQualifierResolver,
                    typeContainer = ownerFunction, isCovariant = true,
                    containerContext = memberContext,
                    containerApplicabilityType =
                    if (false) // TODO: this.safeAs<FirProperty>()?.isJavaField == true
                        AnnotationTypeQualifierResolver.QualifierApplicabilityType.FIELD
                    else
                        AnnotationTypeQualifierResolver.QualifierApplicabilityType.METHOD_RETURN_TYPE
                ) { it.returnTypeRef.toResolvedTypeRef() }.enhance(
                    this@FirJavaTypeEnhancementTransformer, predefinedEnhancementInfo?.returnTypeInfo
                )
            }
        }

        return signatureParts.type.compose()
    }

    // ==========================================================================================================

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
            this@FirJavaTypeEnhancementTransformer.extractNullability(
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

    private fun FirJavaMethod.computeJvmDescriptor(): String = buildString {
        append(name.asString()) // TODO: Java constructors

        append("(")
        for (parameter in valueParameters) {
            // TODO: appendErasedType(parameter.returnTypeRef)
        }
        append(")")

        if ((returnTypeRef as FirJavaTypeRef).isVoid()) {
            append("V")
        } else {
            // TODO: appendErasedType(returnTypeRef)
        }
    }

    private fun FirJavaTypeRef.isVoid(): Boolean {
        return type is JavaPrimitiveType && type.type == null
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
            methodContext.copyWithNewDefaultTypeQualifiers(typeQualifierResolver, this@FirJavaTypeEnhancementTransformer, it.annotations)
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
                typeQualifierResolver, this@FirJavaTypeEnhancementTransformer, collector(this).annotations
            ),
            containerApplicabilityType
        )
    }

    @Suppress("unused")
    internal open class PartEnhancementResult(
        val type: FirResolvedTypeRef,
        val wereChanges: Boolean,
        val containsFunctionN: Boolean
    )
}