/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.actions.internal

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.command.CommandProcessor
import com.intellij.testFramework.EditorTestUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.nextLeafs

class TestEnterActionIndentAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val file = e.getData(PlatformDataKeys.PSI_FILE) as? KtFile ?: return
        val editor = e.getData(PlatformDataKeys.EDITOR) ?: return
        val project = file.project
        val leafs = file.findElementAt(0)?.nextLeafs ?: return
        for (leaf in leafs) {
            editor.caretModel.moveToOffset(leaf.textOffset)

            CommandProcessor.getInstance().executeCommand(project, {
                EditorTestUtil.executeAction(editor, IdeActions.ACTION_EDITOR_ENTER)
            }, "", null, editor.document)

            CommandProcessor.getInstance().executeCommand(project, {
                EditorTestUtil.executeAction(editor, IdeActions.ACTION_UNDO)
            }, "", null, editor.document)
        }
    }
}