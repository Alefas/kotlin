/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java.scopes

import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.java.toConeKotlinType
import org.jetbrains.kotlin.fir.java.types.FirJavaTypeRef
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.scopes.impl.FirAbstractProviderBasedScope
import org.jetbrains.kotlin.fir.scopes.impl.FirClassDeclaredMemberScope
import org.jetbrains.kotlin.fir.symbols.ConeCallableSymbol
import org.jetbrains.kotlin.fir.symbols.ConeFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinErrorType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.load.java.structure.JavaClassifierType
import org.jetbrains.kotlin.name.Name

class JavaClassUseSiteScope(
    session: FirSession,
    private val superTypesScope: FirScope,
    private val declaredMemberScope: FirClassDeclaredMemberScope
) : FirAbstractProviderBasedScope(session, lookupInFir = true) {
    //base symbol as key, overridden as value
    val overrides = mutableMapOf<ConeFunctionSymbol, ConeFunctionSymbol?>()

    private fun FirTypeRef.toConeKotlinType(): ConeKotlinType =
        when (this) {
            // TODO: remove type arguments here and below
            is FirResolvedTypeRef -> type
            is FirJavaTypeRef -> {
                val javaType = type
                if (javaType is JavaClassifierType) javaType.toConeKotlinType(session)
                else ConeKotlinErrorType("Unexpected Java type in JavaClassUseSiteScope: ${javaType::class.java}")
            }
            else -> ConeKotlinErrorType("Unexpected type reference in JavaClassUseSiteScope: ${this::class.java}")
        }

    @Suppress("UNUSED_PARAMETER")
    private fun isSubtypeOf(subType: ConeKotlinType, superType: ConeKotlinType): Boolean {
        // TODO: introduce normal sub-typing
        return true
    }

    private fun isSubtypeOf(subType: FirTypeRef, superType: FirTypeRef) =
        isSubtypeOf(subType.toConeKotlinType(), superType.toConeKotlinType())

    @Suppress("UNUSED_PARAMETER")
    private fun isEqualTypes(a: ConeKotlinType, b: ConeKotlinType): Boolean {
        // TODO: introduce normal type comparison
        return true
    }

    private fun isEqualTypes(a: FirTypeRef, b: FirTypeRef) =
        isEqualTypes(a.toConeKotlinType(), b.toConeKotlinType())

    private fun isOverriddenFunCheck(member: FirNamedFunction, self: FirNamedFunction): Boolean {
        return member.valueParameters.size == self.valueParameters.size &&
                member.valueParameters.zip(self.valueParameters).all { (memberParam, selfParam) ->
                    isEqualTypes(memberParam.returnTypeRef, selfParam.returnTypeRef)
                }
    }

    private fun ConeFunctionSymbol.isOverridden(seen: Set<ConeFunctionSymbol>): ConeCallableSymbol? {
        if (overrides.containsKey(this)) return overrides[this]

        fun sameReceivers(memberTypeRef: FirTypeRef?, selfTypeRef: FirTypeRef?): Boolean {
            return when {
                memberTypeRef != null && selfTypeRef != null -> isEqualTypes(memberTypeRef, selfTypeRef)
                else -> memberTypeRef == null && selfTypeRef == null
            }
        }

        val self = (this as FirFunctionSymbol).fir as FirNamedFunction
        val overriding = seen.firstOrNull {
            val member = (it as FirFunctionSymbol).fir as FirNamedFunction
            member.isOverride && self.modality != Modality.FINAL
                    && sameReceivers(member.receiverTypeRef, self.receiverTypeRef)
                    && isSubtypeOf(member.returnTypeRef, self.returnTypeRef)
                    && isOverriddenFunCheck(member, self)
        } // TODO: two or more overrides for one fun?
        overrides[this] = overriding
        return overriding
    }

    override fun processFunctionsByName(name: Name, processor: (ConeFunctionSymbol) -> ProcessorAction): ProcessorAction {
        val seen = mutableSetOf<ConeFunctionSymbol>()
        if (!declaredMemberScope.processFunctionsByName(name) {
                seen += it
                processor(it)
            }
        ) return ProcessorAction.STOP

        return superTypesScope.processFunctionsByName(name) {

            val overriddenBy = it.isOverridden(seen)
            if (overriddenBy == null) {
                processor(it)
            } else {
                ProcessorAction.NEXT
            }
        }
    }
}
