/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.configuration.utils

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.NonClasspathDirectoriesScope
import com.intellij.util.containers.ConcurrentFactoryMap
import org.jetbrains.kotlin.idea.core.script.ScriptConfigurationManager
import org.jetbrains.kotlin.idea.util.getProjectJdkTableSafe
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import java.io.File
import java.nio.file.FileSystems

abstract class ScriptClassRootsCache(
    private val project: Project,
    private val roots: ScriptClassRootsStorage.Companion.ScriptClassRoots
) {
    protected abstract val fileToConfiguration: (VirtualFile) -> ScriptCompilationConfigurationWrapper?

    protected abstract val rootsCacheKey: ScriptClassRootsStorage.Companion.Key

    abstract val firstScriptSdk: Sdk?

    abstract fun getScriptSdk(file: VirtualFile): Sdk?

    abstract fun contains(file: VirtualFile): Boolean

    class Fat(
        val scriptConfiguration: ScriptCompilationConfigurationWrapper,
        val classFilesScope: GlobalSearchScope
    )

    val allDependenciesClassFiles by lazy {
        ScriptClassRootsStorage.getInstance(project).loadClasspathRoots(rootsCacheKey)
    }

    val allDependenciesSources by lazy {
        ScriptClassRootsStorage.getInstance(project).loadSourcesRoots(rootsCacheKey)
    }

    val allDependenciesClassFilesScope by lazy {
        NonClasspathDirectoriesScope.compose(allDependenciesClassFiles)
    }

    val allDependenciesSourcesScope by lazy {
        NonClasspathDirectoriesScope.compose(allDependenciesSources)
    }

    private val scriptsDependenciesCache: MutableMap<VirtualFile, Fat> =
        ConcurrentFactoryMap.createWeakMap { file ->
            val configuration = fileToConfiguration(file) ?: return@createWeakMap null

            val scriptSdk = getScriptSdk(file)
            return@createWeakMap if (scriptSdk != null && !scriptSdk.isAlreadyIndexed(project)) {
                Fat(
                    configuration,
                    NonClasspathDirectoriesScope.compose(
                        scriptSdk.rootProvider.getFiles(OrderRootType.CLASSES).toList() + roots.classpathFiles.toVirtualFiles()
                    )
                )

            } else {
                Fat(
                    configuration,
                    NonClasspathDirectoriesScope.compose(roots.classpathFiles.toVirtualFiles())
                )
            }
        }

    fun getScriptDependenciesClassFilesScope(file: VirtualFile): GlobalSearchScope {
        return scriptsDependenciesCache[file]?.classFilesScope ?: GlobalSearchScope.EMPTY_SCOPE
    }

    fun getScriptConfiguration(file: VirtualFile): ScriptCompilationConfigurationWrapper? {
        return scriptsDependenciesCache[file]?.scriptConfiguration
    }

    fun hasNotCachedRoots(roots: ScriptClassRootsStorage.Companion.ScriptClassRoots): Boolean {
        return !ScriptClassRootsStorage.getInstance(project).containsAll(rootsCacheKey, roots)
    }

    fun saveClassRootsToStorage() {
        val rootsStorage = ScriptClassRootsStorage.getInstance(project)
        if (roots.classpathFiles.isEmpty() && roots.sourcesFiles.isEmpty() && roots.sdks.isEmpty()) return

        rootsStorage.save(rootsCacheKey, roots)
    }

    //todo
    private fun String.toVirtualFile(): VirtualFile {
        if (this.endsWith("!/")) {
            StandardFileSystems.jar()?.findFileByPath(this)?.let {
                return it
            }
        }
        StandardFileSystems.jar()?.findFileByPath("$this!/")?.let {
            return it
        }
        StandardFileSystems.local()?.findFileByPath(this)?.let {
            return it
        }


        // TODO: report this somewhere, but do not throw: assert(res != null, { "Invalid classpath entry '$this': exists: ${exists()}, is directory: $isDirectory, is file: $isFile" })

        return VfsUtil.findFile(FileSystems.getDefault().getPath(this), true)!!
    }

    private fun Collection<String>.toVirtualFiles() =
        map { it.toVirtualFile() }

    companion object {
        fun toStringValues(prop: Collection<File>): Set<String> {
            return prop.mapNotNull {
                when {
                    it.isDirectory -> it.absolutePath
                    it.isFile -> it.absolutePath
                    else -> null
                }
            }.toSet()
        }

        fun getScriptSdkOfDefault(javaHomeStr: File?, project: Project): Sdk? {
            return getScriptSdk(javaHomeStr) ?: ScriptConfigurationManager.getScriptDefaultSdk(project)
        }

        fun getScriptSdk(javaHomeStr: File?): Sdk? {
            // workaround for mismatched gradle wrapper and plugin version
            val javaHome = try {
                javaHomeStr?.let { VfsUtil.findFileByIoFile(it, true) }
            } catch (e: Throwable) {
                null
            } ?: return null

            return getProjectJdkTableSafe().allJdks.find { it.homeDirectory == javaHome }
        }

        fun Sdk.isAlreadyIndexed(project: Project): Boolean {
            return ModuleManager.getInstance(project).modules.any { ModuleRootManager.getInstance(it).sdk == this }
        }

    }
}

