/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir

import org.jetbrains.kotlin.fir.resolve.AbstractFirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.FirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.impl.FirCompositeSymbolProvider
import org.jetbrains.kotlin.fir.visitors.FirTransformer

interface FirSessionWithTransformation : FirSession {
    fun <D> launchTransformation(transformer: FirTransformer<D>, data: D, providerFilter: (AbstractFirSymbolProvider) -> Boolean) {
        val symbolProvider = service<FirSymbolProvider>()
        if (symbolProvider is FirCompositeSymbolProvider) {
            symbolProvider.providers.filterIsInstance<AbstractFirSymbolProvider>().filter(providerFilter).forEach {
                it.transformTopLevelClasses(transformer, data)
            }
        } else if (symbolProvider is AbstractFirSymbolProvider && providerFilter(symbolProvider)) {
            symbolProvider.transformTopLevelClasses(transformer, data)
        }
    }
}