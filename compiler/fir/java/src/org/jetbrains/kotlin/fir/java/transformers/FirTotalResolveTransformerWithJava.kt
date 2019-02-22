/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java.transformers

import org.jetbrains.kotlin.fir.FirSessionWithTransformation
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.java.JavaSymbolProvider
import org.jetbrains.kotlin.fir.resolve.transformers.FirTotalResolveTransformer

class FirTotalResolveTransformerWithJava : FirTotalResolveTransformer() {
    override fun additionalTransform(files: List<FirFile>) {
        val sessions = files.mapTo(linkedSetOf()) { it.session }
        for (session in sessions) {
            if (session is FirSessionWithTransformation) {
                session.launchTransformation(FirJavaTypeEnhancementTransformer(session), null) { provider ->
                    provider is JavaSymbolProvider
                }
            }
        }
    }
}