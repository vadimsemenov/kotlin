/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.scripting.gradle

import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsCache
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsStorage
import org.jetbrains.kotlin.idea.scripting.gradle.importing.GradleKtsContext
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper

internal class GradleClassRootsCache(
    configuration: Configuration,
    override val fileToConfiguration: (VirtualFile) -> ScriptCompilationConfigurationWrapper?
) : ScriptClassRootsCache(configuration.context.project, extractRoots(configuration)) {

    override val rootsCacheKey = ScriptClassRootsStorage.Companion.Key("gradle")

    override fun getScriptSdk(file: VirtualFile): Sdk? {
        return firstScriptSdk
    }

    override val firstScriptSdk: Sdk? = getScriptSdk(configuration.context.javaHome)

    // TODO what should we do if no configuration is loaded yet
    override fun contains(file: VirtualFile): Boolean = true

    companion object {
        fun extractRoots(configuration: Configuration): ScriptClassRootsStorage.Companion.ScriptClassRoots {
            return ScriptClassRootsStorage.Companion.ScriptClassRoots(
                configuration.classFilePath,
                configuration.sourcePath,
                getScriptSdk(configuration.context.javaHome)?.let { setOf(it) } ?: setOf()
            )
        }
    }
}

internal class EmptyClassRoots(project: Project) :
    ScriptClassRootsCache(project, ScriptClassRootsStorage.Companion.ScriptClassRoots(emptySet(), emptySet(), emptySet())) {
    override val fileToConfiguration: (VirtualFile) -> ScriptCompilationConfigurationWrapper?
        get() = { null }

    override fun contains(file: VirtualFile): Boolean = true

    override fun getScriptSdk(file: VirtualFile): Sdk? = null

    override val firstScriptSdk: Sdk? = null

    override val rootsCacheKey = ScriptClassRootsStorage.Companion.Key("empty")
}