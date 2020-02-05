/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.formatter

import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import com.intellij.psi.impl.source.codeStyle.SemanticEditorPosition
import com.intellij.psi.impl.source.codeStyle.lineIndent.FormatterBasedLineIndentProvider
import com.intellij.psi.impl.source.codeStyle.lineIndent.JavaLikeLangLineIndentProvider
import com.intellij.psi.tree.IElementType
import org.apache.log4j.Logger
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.lexer.KtTokens
import kotlin.math.max
import kotlin.math.min

class KotlinLineIndentProvider : JavaLikeLangLineIndentProvider() {
    private val formatterBasedProvider = FormatterBasedLineIndentProvider()

    private val LOG = Logger.getLogger(this.javaClass)

    override fun isSuitableForLanguage(language: Language): Boolean = language.isKindOf(KotlinLanguage.INSTANCE)

    @Suppress("SuspiciousEqualsCombination")
    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        val result = super.getLineIndent(project, editor, language, offset)
        val formatterBasedResult = formatterBasedProvider.getLineIndent(project, editor, language, offset)

        if (result !== LineIndentProvider.DO_NOT_ADJUST && result != null && result != formatterBasedResult) {
            val message = "Java-like indent is not equals to formatter based indent text:\n${
            editor.document.text.substring(max(offset - 30, 0), min(offset + 30, editor.document.textLength))
            }"
            println(message)
            LOG.error(message, Throwable(message))
        } else if (result === LineIndentProvider.DO_NOT_ADJUST || result == null) {
            println("Java-like indent is empty...")
            LOG.info("Java-like indent is empty...")
        }

        return formatterBasedResult
    }

    override fun mapType(tokenType: IElementType): SemanticEditorPosition.SyntaxElement? = SYNTAX_MAP[tokenType]

    companion object {
        private val SYNTAX_MAP = linkedMapOf(
            KtTokens.WHITE_SPACE to JavaLikeElement.Whitespace,
            KtTokens.SEMICOLON to JavaLikeElement.Semicolon,
            KtTokens.LBRACE to JavaLikeElement.BlockOpeningBrace,
            KtTokens.RBRACE to JavaLikeElement.BlockClosingBrace,
            KtTokens.LBRACKET to JavaLikeElement.ArrayOpeningBracket,
            KtTokens.RBRACKET to JavaLikeElement.ArrayClosingBracket,
            KtTokens.RPAR to JavaLikeElement.RightParenthesis,
            KtTokens.LPAR to JavaLikeElement.LeftParenthesis,
            KtTokens.COLON to JavaLikeElement.Colon,
            KtNodeTypes.WHEN_ENTRY to JavaLikeElement.SwitchCase,
            KtTokens.ELSE_KEYWORD to JavaLikeElement.ElseKeyword,
            KtTokens.IF_KEYWORD to JavaLikeElement.IfKeyword,
            KtTokens.WHILE_KEYWORD to JavaLikeElement.IfKeyword,
            KtTokens.FOR_KEYWORD to JavaLikeElement.ForKeyword,
            KtTokens.WHEN_KEYWORD to JavaLikeElement.ForKeyword,
            KtTokens.TRY_KEYWORD to JavaLikeElement.TryKeyword,
            KtTokens.DO_KEYWORD to JavaLikeElement.DoKeyword,
            KtTokens.BLOCK_COMMENT to JavaLikeElement.BlockComment,
            KtTokens.DOC_COMMENT to JavaLikeElement.BlockComment,
            KtTokens.EOL_COMMENT to JavaLikeElement.LineComment,
            KtTokens.COMMA to JavaLikeElement.Comma
        )
    }
}