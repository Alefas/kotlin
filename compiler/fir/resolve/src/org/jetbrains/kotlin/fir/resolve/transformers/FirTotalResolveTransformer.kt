/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.transformers

import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.visitors.FirTransformer

open class FirTotalResolveTransformer {

    private val firstStageTransformers: List<FirTransformer<Nothing?>> = listOf(
        FirImportResolveTransformer(),
        FirTypeResolveTransformer(),
        FirStatusResolveTransformer()
    )

    private val secondStageTransformers: List<FirTransformer<Nothing?>> = listOf(
        FirAccessResolveTransformer()
    )

    protected open fun additionalTransform(files: List<FirFile>) {}

    val transformers: List<FirTransformer<Nothing?>> get() = firstStageTransformers + secondStageTransformers

    fun processFile(firFile: FirFile) {
        for (transformer in firstStageTransformers) {
            firFile.transform<FirFile, Nothing?>(transformer, null)
        }
        additionalTransform(listOf(firFile))
        for (transformer in secondStageTransformers) {
            firFile.transform<FirFile, Nothing?>(transformer, null)
        }
    }

    fun processFiles(files: List<FirFile>) {
        for (transformer in firstStageTransformers) {
            for (firFile in files) {
                firFile.transform<FirFile, Nothing?>(transformer, null)
            }
        }
        additionalTransform(files)
        for (transformer in secondStageTransformers) {
            for (firFile in files) {
                firFile.transform<FirFile, Nothing?>(transformer, null)
            }
        }
    }
}