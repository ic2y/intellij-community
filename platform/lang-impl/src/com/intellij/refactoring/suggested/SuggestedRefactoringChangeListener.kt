// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.refactoring.suggested

import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.impl.PsiTreeChangeEventImpl
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.hasErrorElementInRange

class SuggestedRefactoringChangeListener(
  private val project: Project,
  private val watcher: SuggestedRefactoringSignatureWatcher
) : Disposable
{
  private val psiDocumentManager = PsiDocumentManager.getInstance(project)
  private val newIdentifierWatcher = NewIdentifierWatcher(5)

  private data class SignatureEditingState(
    val signatureRangeMarker: RangeMarker,
    val importRangeMarker: RangeMarker?,
    val isRefactoringSuppressed: Boolean
  )

  private var editingState: SignatureEditingState? = null

  private var isFirstChangeInsideCommand = false

  fun attach() {
    EditorFactory.getInstance().eventMulticaster.addDocumentListener(MyDocumentListener(), this)
    PsiManager.getInstance(project).addPsiTreeChangeListener(MyPsiTreeChangeListener(), this)

    project.messageBus.connect(this).subscribe(CommandListener.TOPIC, object : CommandListener {
      override fun commandStarted(event: CommandEvent) {
        isFirstChangeInsideCommand = true
      }
    })
  }

  override fun dispose() {
  }

  fun reset() {
    if (editingState != null) {
      editingState!!.signatureRangeMarker.dispose()
      editingState!!.importRangeMarker?.dispose()
      if (!editingState!!.isRefactoringSuppressed) {
        watcher.reset()
      }
      editingState = null
    }
  }

  fun suppressForCurrentDeclaration() {
    if (editingState != null && !editingState!!.isRefactoringSuppressed) {
      editingState = editingState!!.copy(isRefactoringSuppressed = true)
      watcher.reset()
    }
  }

  private fun processBeforeFirstChangeWithPsiAndDocumentInSync(
    psiFile: PsiFile,
    document: Document,
    changeRange: TextRange,
    refactoringSupport: SuggestedRefactoringSupport
  ) {
    if (editingState != null) return

    // it doesn't make sense start track signature changes not in active editor
    val editors = EditorFactory.getInstance().getEditors(document, project)
    if (editors.isEmpty()) return

    // suppress if live template is active - otherwise refactoring appears for create from usage and other features
    if (editors.any { TemplateManager.getInstance(project).getActiveTemplate(it) != null }) return

    fun declarationByOffsetInSignature(offset: Int): PsiElement? {
      val declaration = refactoringSupport.declarationByOffset(psiFile, offset) ?: return null
      val signatureRange = refactoringSupport.signatureRange(declaration) ?: return null
      return declaration.takeIf { offset in signatureRange }
    }

    val truncatedChangeRange = changeRange.stripWhitespace(document.charsSequence)
    val offset = truncatedChangeRange.startOffset
    var declaration = declarationByOffsetInSignature(offset)
    if (declaration == null && changeRange.isEmpty) { // for text insertion we might need to look to the left and to the right
      val whitespaceRange = TextRange(offset, offset).extendWithWhitespace(document.charsSequence)
      val leftDeclaration = if (whitespaceRange.startOffset > 0)
        declarationByOffsetInSignature(whitespaceRange.startOffset - 1)
      else
        null
      val rightDeclaration = if (whitespaceRange.endOffset > offset)
        declarationByOffsetInSignature(whitespaceRange.endOffset)
      else
        null
      declaration = leftDeclaration ?: rightDeclaration
      //TODO: support tracking both declarations for a while
    }
    if (declaration == null) return

    if (refactoringSupport.hasSyntaxError(declaration)) return

    val signatureRange = refactoringSupport.signatureRange(declaration) ?: return
    val extendedSignatureRange = signatureRange.union(truncatedChangeRange)

    val signatureRangeMarker = document.createRangeMarker(extendedSignatureRange).apply {
      isGreedyToLeft = true
      isGreedyToRight = true
    }
    val importRangeMarker = refactoringSupport.importsRange(psiFile)
      ?.let { document.createRangeMarker(it) }
      ?.apply {
        isGreedyToLeft = true
        isGreedyToRight = true
      }

    val refactoringSuppressed = shouldSuppressRefactoring(declaration, document, refactoringSupport)

    editingState = SignatureEditingState(signatureRangeMarker, importRangeMarker, refactoringSuppressed)
    if (!refactoringSuppressed) {
      watcher.editingStarted(declaration, refactoringSupport)
    }
  }

  private fun shouldSuppressRefactoring(
    declaration: PsiElement,
    document: Document,
    refactoringSupport: SuggestedRefactoringSupport
  ): Boolean {
    // suppress for declaration being just typed
    if (newIdentifierWatcher.lastDocument == document) {
      val nameRange = refactoringSupport.nameRange(declaration)
      if (nameRange != null) {
        val ranges = newIdentifierWatcher.lastNewIdentifierRanges()
        val index = ranges.lastIndexOf(nameRange)
        if (index >= 0) {
          // check that all identifiers typed after declaration name are inside its signature
          val signatureRange = refactoringSupport.signatureRange(declaration)!!
          return ranges.drop(index + 1).all { it in signatureRange }
        }
      }
    }

    return false
  }

  private inner class MyDocumentListener : DocumentListener {
    private var isActionOnAllCommittedScheduled = false

    override fun beforeDocumentChange(event: DocumentEvent) {
      val document = event.document
      val psiFile = psiDocumentManager.getCachedPsiFile(document) ?: return
      if (!psiFile.isPhysical || psiFile is PsiCodeFragment) return

      val firstChangeInsideCommand = isFirstChangeInsideCommand
      isFirstChangeInsideCommand = false

      val refactoringSupport = SuggestedRefactoringSupport.forLanguage(psiFile.language) ?: return

      if (shouldAbortSignatureEditing(event)) {
        reset()
      }

      if (firstChangeInsideCommand &&
          psiDocumentManager.isCommitted(document) &&
          !psiDocumentManager.isDocumentBlockedByPsi(document)
      ) {
        processBeforeFirstChangeWithPsiAndDocumentInSync(psiFile, document, event.oldRange, refactoringSupport)
      }
    }

    private fun shouldAbortSignatureEditing(event: DocumentEvent): Boolean {
      val state = editingState ?: return false
      if (!state.signatureRangeMarker.isValid) return true
      if (state.signatureRangeMarker.document != event.document) return true
      if (state.importRangeMarker != null && !state.importRangeMarker.isValid) return true

      val signatureRange = state.signatureRangeMarker.range!!
      val importRange = state.importRangeMarker?.range

      if (event.oldRange !in signatureRange && (importRange == null || event.oldRange !in importRange)) {
        return event.oldFragment.isNotBlank() || event.newFragment.isNotBlank()
      }

      return false
    }

    override fun documentChanged(event: DocumentEvent) {
      if (event.oldLength == 0 && event.newLength == 0) return

      val document = event.document
      val psiFile = psiDocumentManager.getCachedPsiFile(document) ?: return
      if (!psiFile.isPhysical || psiFile is PsiCodeFragment) return

      newIdentifierWatcher.documentChanged(event, psiFile.language)

      val editingState = editingState ?: return
      val signatureRangeMarker = editingState.signatureRangeMarker
      if (!signatureRangeMarker.isValid || editingState.importRangeMarker != null && !editingState.importRangeMarker.isValid) {
        reset()
        return
      }

      if (!isActionOnAllCommittedScheduled) {
        isActionOnAllCommittedScheduled = true
        psiDocumentManager.performWhenAllCommitted(this::performWhenAllCommitted)
      }
    }

    private fun performWhenAllCommitted() {
      isActionOnAllCommittedScheduled = false

      val editingState = editingState ?: return
      val watchedRange = editingState.signatureRangeMarker.range
      if (watchedRange == null) {
        reset()
        return
      }

      val document = editingState.signatureRangeMarker.document
      val psiFile = psiDocumentManager.getPsiFile(document) ?: return
      val refactoringSupport = SuggestedRefactoringSupport.forLanguage(psiFile.language) ?: return

      val chars = document.charsSequence
      val strippedWatchedRange = watchedRange.stripWhitespace(chars)
      val declaration = refactoringSupport.declarationByOffset(psiFile, strippedWatchedRange.startOffset)
      val signatureRange = declaration?.let { refactoringSupport.signatureRange(it) }

      if (declaration == null || signatureRange == null || strippedWatchedRange != signatureRange.stripWhitespace(chars)) {
        val range = signatureRange?.union(watchedRange) ?: watchedRange
        if (psiFile.hasErrorElementInRange(range)) {
          if (!editingState.isRefactoringSuppressed) {
            watcher.inconsistentState()
          }
        }
        else {
          reset()
        }
        return
      }

      if (!editingState.isRefactoringSuppressed) {
        watcher.nextSignature(declaration, refactoringSupport)
      }
    }
  }

  private inner class MyPsiTreeChangeListener : PsiTreeChangeAdapter() {
    override fun beforeChildAddition(event: PsiTreeChangeEvent) {
      processBeforeEvent(event)
    }

    override fun beforeChildRemoval(event: PsiTreeChangeEvent) {
      processBeforeEvent(event)
    }

    override fun beforeChildReplacement(event: PsiTreeChangeEvent) {
      processBeforeEvent(event)
    }

    override fun beforeChildrenChange(event: PsiTreeChangeEvent) {
      processBeforeEvent(event)
    }

    override fun beforeChildMovement(event: PsiTreeChangeEvent) {
      processBeforeEvent(event)
    }

    private fun processBeforeEvent(event: PsiTreeChangeEvent) {
      if (!isFirstChangeInsideCommand) return

      val psiFile = event.file ?: return
      val document = psiDocumentManager.getCachedDocument(psiFile)

      if (document == null) {
        isFirstChangeInsideCommand = false
        return
      }

      if (psiDocumentManager.isUncommited(document)) return // commit in progress, changes have been processed by DocumentListener

      isFirstChangeInsideCommand = false

      if (!(psiFile as PsiFileImpl).isContentsLoaded) return // no AST loaded

      val refactoringSupport = SuggestedRefactoringSupport.forLanguage(psiFile.language) ?: return
      event as PsiTreeChangeEventImpl
      val changeRange = TextRange.from(event.offset, event.oldLength)
      processBeforeFirstChangeWithPsiAndDocumentInSync(psiFile, document, changeRange, refactoringSupport)
    }
  }
}

interface SuggestedRefactoringSignatureWatcher {
  fun editingStarted(declaration: PsiElement, refactoringSupport: SuggestedRefactoringSupport)
  fun nextSignature(declaration: PsiElement, refactoringSupport: SuggestedRefactoringSupport)
  fun inconsistentState()
  fun reset()
}
