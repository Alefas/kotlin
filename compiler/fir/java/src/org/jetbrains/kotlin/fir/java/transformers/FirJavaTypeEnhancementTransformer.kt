/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java.transformers

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.java.declarations.FirJavaClass
import org.jetbrains.kotlin.fir.java.declarations.FirJavaMethod
import org.jetbrains.kotlin.fir.java.declarations.FirJavaValueParameter
import org.jetbrains.kotlin.fir.java.scopes.JavaClassUseSiteScope
import org.jetbrains.kotlin.fir.java.toConeKotlinType
import org.jetbrains.kotlin.fir.java.types.FirJavaTypeRef
import org.jetbrains.kotlin.fir.resolve.transformers.FirAbstractTreeTransformerWithSuperTypes
import org.jetbrains.kotlin.fir.scopes.impl.FirClassDeclaredMemberScope
import org.jetbrains.kotlin.fir.scopes.impl.FirCompositeScope
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassErrorType
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.impl.FirResolvedTypeRefImpl
import org.jetbrains.kotlin.fir.visitors.CompositeTransformResult
import org.jetbrains.kotlin.fir.visitors.compose
import org.jetbrains.kotlin.load.java.structure.JavaClassifierType

class FirJavaTypeEnhancementTransformer : FirAbstractTreeTransformerWithSuperTypes(reversedScopePriority = true) {

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

    override fun transformRegularClass(regularClass: FirRegularClass, data: Nothing?): CompositeTransformResult<FirDeclaration> {
        if (regularClass !is FirJavaClass) return regularClass.compose()
        return withScopeCleanup {
            towerScope.scopes += regularClass.buildUseSiteScope()
            super.transformRegularClass(regularClass, data)
        }
    }

    override fun transformNamedFunction(namedFunction: FirNamedFunction, data: Nothing?): CompositeTransformResult<FirDeclaration> {
        if (namedFunction !is FirJavaMethod) return namedFunction.compose()
        return super.transformNamedFunction(namedFunction, data)
    }

    override fun transformValueParameter(valueParameter: FirValueParameter, data: Nothing?): CompositeTransformResult<FirDeclaration> {
        if (valueParameter !is FirJavaValueParameter) return valueParameter.compose()
        return super.transformValueParameter(valueParameter, data)
    }

    override fun transformTypeRef(typeRef: FirTypeRef, data: Nothing?): CompositeTransformResult<FirTypeRef> {
        if (typeRef !is FirJavaTypeRef) return typeRef.compose()
        val javaType = typeRef.type as? JavaClassifierType ?: return super.transformTypeRef(typeRef, data)
        val upperBoundType = javaType.toConeKotlinType(typeRef.session, isNullable = true)
        val lowerBoundType = javaType.toConeKotlinType(typeRef.session, isNullable = false)
        return FirResolvedTypeRefImpl(
            typeRef.session, null, ConeFlexibleType(lowerBoundType, upperBoundType),
            isMarkedNullable = false,
            annotations = typeRef.annotations
        ).compose()
    }
}