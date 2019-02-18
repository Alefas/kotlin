/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.transformers

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.scopes.impl.FirCompositeScope
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.ConeClassSymbol
import org.jetbrains.kotlin.fir.symbols.ConeTypeAliasSymbol
import org.jetbrains.kotlin.fir.types.ConeAbbreviatedType
import org.jetbrains.kotlin.fir.types.ConeClassErrorType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType

abstract class FirAbstractTreeTransformerWithSuperTypes(reversedScopePriority: Boolean) : FirAbstractTreeTransformer() {
    protected val towerScope = FirCompositeScope(mutableListOf(), reversedPriority = reversedScopePriority)

    protected inline fun <T> withScopeCleanup(crossinline l: () -> T): T {
        val sizeBefore = towerScope.scopes.size
        val result = l()
        val size = towerScope.scopes.size
        assert(size >= sizeBefore)
        repeat(size - sizeBefore) {
            towerScope.scopes.let { it.removeAt(it.size - 1) }
        }
        return result
    }

    protected fun lookupSuperTypes(
        klass: FirRegularClass,
        lookupInterfaces: Boolean,
        deep: Boolean
    ): List<ConeClassLikeType> {
        return mutableListOf<ConeClassLikeType>().also {
            if (lookupInterfaces) klass.symbol.collectSuperTypes(it, deep)
            else klass.symbol.collectSuperClasses(it)
        }
    }

    private tailrec fun ConeClassLikeType.computePartialExpansion(): ConeClassLikeType? {
        return when (this) {
            is ConeAbbreviatedType -> directExpansion.takeIf { it !is ConeClassErrorType }?.computePartialExpansion()
            else -> return this
        }
    }

    private tailrec fun ConeClassLikeSymbol.collectSuperClasses(list: MutableList<ConeClassLikeType>) {
        when (this) {
            is ConeClassSymbol -> {
                val superClassType =
                    this.superTypes
                        .map { it.computePartialExpansion() }
                        .firstOrNull {
                            it !is ConeClassErrorType && (it?.symbol as? ConeClassSymbol)?.kind == ClassKind.CLASS
                        } ?: return
                list += superClassType
                superClassType.symbol.collectSuperClasses(list)
            }
            is ConeTypeAliasSymbol -> {
                val expansion = expansionType?.computePartialExpansion() ?: return
                expansion.symbol.collectSuperClasses(list)
            }
            else -> error("?!id:1")
        }
    }

    private fun ConeClassLikeSymbol.collectSuperTypes(list: MutableList<ConeClassLikeType>, deep: Boolean) {
        when (this) {
            is ConeClassSymbol -> {
                val superClassTypes =
                    this.superTypes.mapNotNull { it.computePartialExpansion() }
                list += superClassTypes
                if (deep)
                    superClassTypes.forEach {
                        if (it !is ConeClassErrorType) {
                            it.symbol.collectSuperTypes(list, deep)
                        }
                    }
            }
            is ConeTypeAliasSymbol -> {
                val expansion = expansionType?.computePartialExpansion() ?: return
                expansion.symbol.collectSuperTypes(list, deep)
            }
            else -> error("?!id:1")
        }
    }
}