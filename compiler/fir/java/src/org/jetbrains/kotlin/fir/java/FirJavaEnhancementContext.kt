/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.load.java.AnnotationTypeQualifierResolver
import org.jetbrains.kotlin.load.java.lazy.JavaTypeQualifiersByElementType
import org.jetbrains.kotlin.load.java.lazy.NullabilityQualifierWithApplicability
import org.jetbrains.kotlin.load.java.lazy.QualifierByApplicabilityType

class FirJavaEnhancementContext private constructor(
    val session: FirSession,
    delegateForDefaultTypeQualifiers: Lazy<JavaTypeQualifiersByElementType?>
) {
    constructor(session: FirSession, typeQualifiersComputation: () -> JavaTypeQualifiersByElementType?) :
            this(session, lazy(LazyThreadSafetyMode.NONE, typeQualifiersComputation))

    val defaultTypeQualifiers: JavaTypeQualifiersByElementType? by delegateForDefaultTypeQualifiers

    val moduleInfo get() = session.moduleInfo
}

fun extractDefaultNullabilityQualifier(
    typeQualifierResolver: FirAnnotationTypeQualifierResolver,
    signatureEnhancement: FirSignatureEnhancement,
    annotationCall: FirAnnotationCall
): NullabilityQualifierWithApplicability? {
    typeQualifierResolver.resolveQualifierBuiltInDefaultAnnotation(annotationCall)?.let { return it }

    val (typeQualifier, applicability) =
        typeQualifierResolver.resolveTypeQualifierDefaultAnnotation(annotationCall)
            ?: return null

    val jsr305State = with(typeQualifierResolver) {
        resolveJsr305CustomState(annotationCall) ?: resolveJsr305AnnotationState(typeQualifier)
    }

    if (jsr305State.isIgnore) {
        return null
    }

    val nullabilityQualifier = signatureEnhancement.extractNullability(
        typeQualifierResolver, typeQualifier
    )?.copy(isForWarningOnly = jsr305State.isWarning) ?: return null

    return NullabilityQualifierWithApplicability(nullabilityQualifier, applicability)
}

fun FirJavaEnhancementContext.computeNewDefaultTypeQualifiers(
    typeQualifierResolver: FirAnnotationTypeQualifierResolver,
    signatureEnhancement: FirSignatureEnhancement,
    additionalAnnotations: List<FirAnnotationCall>
): JavaTypeQualifiersByElementType? {
    if (typeQualifierResolver.disabled) return defaultTypeQualifiers

    val nullabilityQualifiersWithApplicability =
        additionalAnnotations.mapNotNull { annotationCall ->
            extractDefaultNullabilityQualifier(
                typeQualifierResolver,
                signatureEnhancement,
                annotationCall
            )
        }

    if (nullabilityQualifiersWithApplicability.isEmpty()) return defaultTypeQualifiers

    val nullabilityQualifiersByType =
        defaultTypeQualifiers?.nullabilityQualifiers?.let(::QualifierByApplicabilityType)
            ?: QualifierByApplicabilityType(AnnotationTypeQualifierResolver.QualifierApplicabilityType::class.java)

    var wasUpdate = false
    for ((nullability, applicableTo) in nullabilityQualifiersWithApplicability) {
        for (applicabilityType in applicableTo) {
            nullabilityQualifiersByType[applicabilityType] = nullability
            wasUpdate = true
        }
    }

    return if (!wasUpdate) defaultTypeQualifiers else JavaTypeQualifiersByElementType(nullabilityQualifiersByType)
}

fun FirJavaEnhancementContext.copyWithNewDefaultTypeQualifiers(
    typeQualifierResolver: FirAnnotationTypeQualifierResolver,
    signatureEnhancement: FirSignatureEnhancement,
    additionalAnnotations: List<FirAnnotationCall>
): FirJavaEnhancementContext =
    when {
        additionalAnnotations.isEmpty() -> this
        else -> FirJavaEnhancementContext(session) {
            computeNewDefaultTypeQualifiers(typeQualifierResolver, signatureEnhancement, additionalAnnotations)
        }
    }