/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.daemon.experimental

import org.jetbrains.kotlin.daemon.common.experimental.CompilerCallbackServicesFacadeClientSide
import org.jetbrains.kotlin.load.kotlin.incremental.components.IncrementalCache
import org.jetbrains.kotlin.load.kotlin.incremental.components.IncrementalCompilationComponents
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.daemon.common.impls.DummyProfilerAsync
import org.jetbrains.kotlin.daemon.common.impls.ProfilerAsync

class RemoteIncrementalCompilationComponentsClient(val facade: CompilerCallbackServicesFacadeClientSide, eventManager: EventManager, val profiler: ProfilerAsync = DummyProfilerAsync()) : IncrementalCompilationComponents {
    override fun getIncrementalCache(target: TargetId): IncrementalCache = RemoteIncrementalCacheClient(facade, target, profiler)
}
