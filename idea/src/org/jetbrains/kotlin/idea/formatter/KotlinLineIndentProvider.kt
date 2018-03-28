/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
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
import com.intellij.util.containers.HashMap
import org.apache.log4j.Logger
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.lexer.KtTokens
import kotlin.math.max
import kotlin.math.min

/**
 * @author Alefas
 */
class KotlinLineIndentProvider : JavaLikeLangLineIndentProvider() {
    private val formatterBasedProvider = FormatterBasedLineIndentProvider()

    private val LOG = Logger.getLogger(this.javaClass)

    override fun isSuitableForLanguage(language: Language): Boolean {
        return language.isKindOf(KotlinLanguage.INSTANCE)
    }

    @Suppress("SuspiciousEqualsCombination")
    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        val result = super.getLineIndent(project, editor, language, offset)
        val formatterBasedResult = formatterBasedProvider.getLineIndent(project, editor, language, offset)

        if (result !== LineIndentProvider.DO_NOT_ADJUST && result != null && result != formatterBasedResult) {
            val message = "Java-like indent is not equals to formatter based indent text:\n${
                editor.document.text.substring(max(offset - 30, 0), min(offset + 30, editor.document.textLength))
            }"
            LOG.error(message, Throwable(message))
        } else if (result === LineIndentProvider.DO_NOT_ADJUST || result == null) {
            LOG.info("Java-like indent is empty...")
        }

        return formatterBasedResult
    }

    override fun mapType(tokenType: IElementType): SemanticEditorPosition.SyntaxElement? {
        return SYNTAX_MAP[tokenType]
    }

    companion object {
        private val SYNTAX_MAP = HashMap<IElementType, SemanticEditorPosition.SyntaxElement>()

        init {
            SYNTAX_MAP[KtTokens.WHITE_SPACE] = JavaLikeElement.Whitespace
            SYNTAX_MAP[KtTokens.SEMICOLON] = JavaLikeElement.Semicolon
            SYNTAX_MAP[KtTokens.LBRACE] = JavaLikeElement.BlockOpeningBrace
            SYNTAX_MAP[KtTokens.RBRACE] = JavaLikeElement.BlockClosingBrace
            SYNTAX_MAP[KtTokens.LBRACKET] = JavaLikeElement.ArrayOpeningBracket
            SYNTAX_MAP[KtTokens.RBRACKET] = JavaLikeElement.ArrayClosingBracket
            SYNTAX_MAP[KtTokens.RPAR] = JavaLikeElement.RightParenthesis
            SYNTAX_MAP[KtTokens.LPAR] = JavaLikeElement.LeftParenthesis
            SYNTAX_MAP[KtTokens.COLON] = JavaLikeElement.Colon
            SYNTAX_MAP[KtTokens.ELSE_KEYWORD] = JavaLikeElement.ElseKeyword
            SYNTAX_MAP[KtTokens.IF_KEYWORD] = JavaLikeElement.IfKeyword
            SYNTAX_MAP[KtTokens.FOR_KEYWORD] = JavaLikeElement.ForKeyword
            SYNTAX_MAP[KtTokens.TRY_KEYWORD] = JavaLikeElement.TryKeyword
            SYNTAX_MAP[KtTokens.DO_KEYWORD] = JavaLikeElement.DoKeyword
            SYNTAX_MAP[KtTokens.BLOCK_COMMENT] = JavaLikeElement.BlockComment
            SYNTAX_MAP[KtTokens.DOC_COMMENT] = JavaLikeElement.BlockComment
            SYNTAX_MAP[KtTokens.EOL_COMMENT] = JavaLikeElement.LineComment
            SYNTAX_MAP[KtTokens.COMMA] = JavaLikeElement.Comma
        }

    }
}