/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.impl.FirTypeParameterImpl
import org.jetbrains.kotlin.fir.resolve.FirSymbolProvider
import org.jetbrains.kotlin.fir.service
import org.jetbrains.kotlin.fir.symbols.ConeClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassErrorType
import org.jetbrains.kotlin.fir.types.ConeKotlinErrorType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.impl.ConeClassTypeImpl
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.load.java.structure.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

internal val JavaModifierListOwner.modality: Modality
    get() = when {
        isAbstract -> Modality.ABSTRACT
        isFinal -> Modality.FINAL
        else -> Modality.OPEN
    }

internal val JavaClass.classKind: ClassKind
    get() = when {
        isAnnotationType -> ClassKind.ANNOTATION_CLASS
        isInterface -> ClassKind.INTERFACE
        isEnum -> ClassKind.ENUM_CLASS
        else -> ClassKind.CLASS
    }

internal fun JavaClassifierType.toConeKotlinType(session: FirSession, isNullable: Boolean = false): ConeKotlinType {
    return when (val classifier = classifier) {
        is JavaClass -> {
            val symbol = session.service<FirSymbolProvider>().getClassLikeSymbolByFqName(classifier.classId!!) as? ConeClassSymbol
            if (symbol == null) ConeKotlinErrorType("Symbol not found, for `${classifier.classId}`")
            else ConeClassTypeImpl(symbol, typeArguments.map { it.toConeProjection(session) }.toTypedArray(), isNullable)
        }
        is JavaTypeParameter -> {
            // TODO: it's unclear how to identify type parameter by the symbol
            // TODO: some type parameter cache (provider?)
            val symbol = createTypeParameterSymbol(session, classifier.name)
            ConeTypeParameterTypeImpl(symbol, isNullable)
        }
        else -> ConeClassErrorType(reason = "Unexpected classifier: $classifier")
    }
}

private fun JavaType.toConeProjection(session: FirSession): ConeKotlinTypeProjection {
    if (this is JavaClassifierType) {
        return toConeKotlinType(session)
    }
    return ConeClassErrorType("Unexpected type argument: $this")
}

internal fun createTypeParameterSymbol(session: FirSession, name: Name): FirTypeParameterSymbol {
    val firSymbol = FirTypeParameterSymbol()
    FirTypeParameterImpl(session, null, firSymbol, name, variance = Variance.INVARIANT, isReified = false)
    return firSymbol
}

