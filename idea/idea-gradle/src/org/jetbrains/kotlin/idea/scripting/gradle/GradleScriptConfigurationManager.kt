/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.scripting.gradle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.idea.core.script.ScriptDefinitionsManager
import org.jetbrains.kotlin.idea.core.script.configuration.ScriptingSupport
import org.jetbrains.kotlin.idea.core.script.configuration.ScriptingSupportHelper
import org.jetbrains.kotlin.idea.core.script.configuration.listener.ScriptConfigurationUpdater
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsCache
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsCache.Companion.isAlreadyIndexed
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsIndexer
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsStorage
import org.jetbrains.kotlin.idea.scripting.gradle.importing.GradleKtsContext
import org.jetbrains.kotlin.idea.scripting.gradle.importing.KotlinDslScriptModel
import org.jetbrains.kotlin.idea.scripting.gradle.importing.toScriptConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.resolve.KotlinScriptDefinitionFromAnnotatedTemplate
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File

internal class Configuration(
    val context: GradleKtsContext,
    models: List<KotlinDslScriptModel>
) {
    val scripts = models.associateBy { it.file }
    val sourcePath = models.flatMapTo(mutableSetOf()) { it.sourcePath }

    val classFilePath: MutableSet<String>

    init {
        while (!ScriptDefinitionsManager.getInstance(context.project).isReady()) {
            Thread.sleep(100)
        }
        // TODO gradle classpath
        val gradleDefinition = ScriptDefinitionsManager.getInstance(context.project).getAllDefinitions().find {
            (it.asLegacyOrNull<KotlinScriptDefinitionFromAnnotatedTemplate>())?.scriptFilePattern?.pattern == ".*\\.gradle\\.kts"
        }!! // todo exception
        val result= hashSetOf<String>()
        result.addAll(gradleDefinition.asLegacyOrNull<KotlinScriptDefinitionFromAnnotatedTemplate>()!!.templateClasspath.map { it.path })
        models.flatMapTo(result) { it.classPath }
        classFilePath = result
    }

    fun scriptModel(file: VirtualFile): KotlinDslScriptModel? {
        return scripts[FileUtil.toSystemDependentName(file.path)]
    }
}

class GradleScriptingSupport(val project: Project) : ScriptingSupport() {
    @Volatile
    private var configuration: Configuration? = null

    private val rootsIndexer = ScriptClassRootsIndexer(project)

    override fun recreateRootsCache(): ScriptClassRootsCache {
        val conf = configuration
        return if (conf != null) {
            GradleClassRootsCache(conf) {
                val model = conf.scriptModel(it)
                model?.toScriptConfiguration(conf.context)
            }
        } else {
            EmptyClassRoots(project)
        }
    }

    fun replace(context: GradleKtsContext, models: List<KotlinDslScriptModel>) {
        KotlinDslScriptModels.write(project, models)

        val old = configuration
        val newConfiguration = Configuration(context, models)

        configuration = newConfiguration

        configurationChangedCallback(old, newConfiguration)
    }

    private fun configurationChangedCallback(
        old: Configuration?,
        newConfiguration: Configuration
    ) {
        rootsIndexer.transaction {
            if (classpathRoots.hasNotCachedRoots(GradleClassRootsCache.extractRoots(newConfiguration))) {
                rootsIndexer.markNewRoot()
            }

            clearClassRootsCaches(project)

            ScriptingSupportHelper.updateHighlighting(project) {
                configuration?.scriptModel(it) != null
            }
        }

        hideNotificationForProjectImport(project)
    }

    private fun shouldReindex(
        old: Configuration?,
        new: Configuration
    ): Boolean {
        if (old == null) return true

        if (classpathRoots.hasNotCachedRoots(GradleClassRootsCache.extractRoots(new))) return true

        //todo
        if (classpathRoots.firstScriptSdk?.isAlreadyIndexed(project) == false) return true

        if (!new.classFilePath.any { it !in old.classFilePath }) return true
        if (!new.sourcePath.any { it !in old.sourcePath }) return true

        return false
    }

    fun load() {
        val gradleProjectSettings = ExternalSystemApiUtil.getSettings(project, GradleConstants.SYSTEM_ID)
            .getLinkedProjectsSettings()
            .filterIsInstance<GradleProjectSettings>().firstOrNull() ?: return

        val gradleExeSettings = ExternalSystemApiUtil.getExecutionSettings<GradleExecutionSettings>(
            project,
            gradleProjectSettings.externalProjectPath,
            GradleConstants.SYSTEM_ID
        )
        val javaHome = File(gradleExeSettings.gradleHome ?: return)

        val models = KotlinDslScriptModels.read(project) ?: return
        val newConfiguration = Configuration(GradleKtsContext(project, javaHome), models)

        configuration = newConfiguration

        configurationChangedCallback(null, newConfiguration)
    }

    init {
        //todo do not run for old gradle
        // should be run after definitions are ready or save models with gradle jars
        ApplicationManager.getApplication().executeOnPooledThread {
            while (!ScriptDefinitionsManager.getInstance(project).isReady()) {
                Thread.sleep(100)
            }
            load()
        }
    }

    fun updateNotification(file: KtFile) {
        val vFile = file.originalFile.virtualFile
        val scriptModel = configuration?.scriptModel(vFile) ?: return

        if (scriptModel.inputs.isUpToDate(project, vFile)) {
            hideNotificationForProjectImport(project)
        } else {
            showNotificationForProjectImport(project)
        }
    }

    override fun isRelated(file: VirtualFile): Boolean {
        if (isGradleKotlinScript(file)) {
            val gradleVersion = getGradleVersion(project)
            if (gradleVersion != null && kotlinDslScriptsModelImportSupported(gradleVersion)) {
                return true
            }
        }

        return false
    }

    override fun clearCaches() {
        // todo: should we drop configuration when script definitions changed?
        //configuration = null
    }

    override fun hasCachedConfiguration(file: KtFile): Boolean =
        configuration?.scriptModel(file.originalFile.virtualFile) != null

    override fun getOrLoadConfiguration(virtualFile: VirtualFile, preloadedKtFile: KtFile?): ScriptCompilationConfigurationWrapper? {
        val configuration = configuration
        if (configuration == null) {
            // todo: show notification "Import gradle project"
            return null
        } else {
            return classpathRoots.getScriptConfiguration(virtualFile)
        }
    }

    override val updater: ScriptConfigurationUpdater
        get() = object : ScriptConfigurationUpdater {
            override fun ensureUpToDatedConfigurationSuggested(file: KtFile) {
                // do nothing for gradle scripts
            }

            // unused symbol inspection should not initiate loading
            override fun ensureConfigurationUpToDate(files: List<KtFile>): Boolean = true

            override fun suggestToUpdateConfigurationIfOutOfDate(file: KtFile) {
                updateNotification(file)
            }
        }

    companion object {
        fun getInstance(project: Project): GradleScriptingSupport {
            return SCRIPTING_SUPPORT.getPoint(project).extensionList.firstIsInstance()
        }
    }
}