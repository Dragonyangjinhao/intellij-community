/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInsight.hints

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.ParameterHintsPresentationManager
import com.intellij.codeInsight.hints.HintInfo.MethodInfo
import com.intellij.codeInsight.hints.settings.Diff
import com.intellij.codeInsight.hints.settings.ParameterNameHintsConfigurable
import com.intellij.codeInsight.hints.settings.ParameterNameHintsSettings
import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.Language
import com.intellij.notification.Notification
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil


class ShowSettingsWithAddedPattern : AnAction() {
  init {
    templatePresentation.description = CodeInsightBundle.message("inlay.hints.show.settings.description")
    templatePresentation.text = CodeInsightBundle.message("inlay.hints.show.settings", "_")
  }

  override fun update(e: AnActionEvent) {
    val file = CommonDataKeys.PSI_FILE.getData(e.dataContext) ?: return
    val editor = CommonDataKeys.EDITOR.getData(e.dataContext) ?: return
    
    val offset = editor.caretModel.offset
    val info = getHintInfoFromProvider(offset, file, editor) ?: return
    
    val text = when (info) {
      is HintInfo.OptionInfo -> "Show Hints Settings..."
      is HintInfo.MethodInfo -> CodeInsightBundle.message("inlay.hints.show.settings", info.getMethodName()) 
    }
    
    e.presentation.setText(text, false)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val file = CommonDataKeys.PSI_FILE.getData(e.dataContext) ?: return
    val editor = CommonDataKeys.EDITOR.getData(e.dataContext) ?: return

    val language = file.language.baseLanguage ?: file.language
    InlayParameterHintsExtension.forLanguage(language) ?: return
    
    val offset = editor.caretModel.offset
    val info = getHintInfoFromProvider(offset, file, editor) ?: return
    
    val newPreselectedPattern = when (info) {
      is HintInfo.OptionInfo -> null
      is HintInfo.MethodInfo -> info.toPattern()
    }
    
    val dialog = ParameterNameHintsConfigurable(language, newPreselectedPattern)
    dialog.show()
  }
}


class BlacklistCurrentMethodIntention : IntentionAction, HighPriorityAction {
  companion object {
    private val presentableText = CodeInsightBundle.message("inlay.hints.blacklist.method")
    private val presentableFamilyName = CodeInsightBundle.message("inlay.hints.intention.family.name")
  }
  
  override fun getText(): String = presentableText
  override fun getFamilyName(): String = presentableFamilyName

  override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
    val language = file.language
    val hintsProvider = InlayParameterHintsExtension.forLanguage(language) ?: return false
    return hintsProvider.isBlackListSupported 
           && hasEditorParameterHintAtOffset(editor, file) 
           && isMethodHintAtOffset(editor, file) 
  }

  private fun isMethodHintAtOffset(editor: Editor, file: PsiFile): Boolean {
    val offset = editor.caretModel.offset
    return getHintInfoFromProvider(offset, file, editor) is MethodInfo
  }

  override fun invoke(project: Project, editor: Editor, file: PsiFile) {
    val offset = editor.caretModel.offset

    val info = getHintInfoFromProvider(offset, file, editor) as? MethodInfo ?: return
    ParameterNameHintsSettings.getInstance().addIgnorePattern(file.language, info.toPattern())
    refreshAllOpenEditors()
    
    showHint(project, file, info)
  }
  
  private fun showHint(project: Project, file: PsiFile, info: MethodInfo) {
    val methodName = info.getMethodName()
    
    val language = file.language
    val listener = NotificationListener { notification, event ->
      when (event.description) {
        "settings" -> showSettings(language)
        "undo" -> undo(language, info)
      }
      notification.expire()
    }

    val notification = Notification("Parameter Name Hints", "Method \"$methodName\" added to blacklist", 
                 "<html><a href='settings'>Show Parameter Hints Settings</a> or <a href='undo'>Undo</a></html>",
                 NotificationType.INFORMATION, listener)
    
    notification.notify(project)
  }
  
  private fun showSettings(language: Language) {
    val dialog = ParameterNameHintsConfigurable(language, null)
    dialog.show()
  }
  
  private fun undo(language: Language, info: MethodInfo) {
    val settings = ParameterNameHintsSettings.getInstance()
    
    val diff = settings.getBlackListDiff(language)
    val updated = diff.added.toMutableSet().apply {
      remove(info.toPattern())
    }
    
    settings.setBlackListDiff(language, Diff(updated, diff.removed))
    refreshAllOpenEditors()
  }

  override fun startInWriteAction() = false
}


class DisableCustomHintsOption: IntentionAction, HighPriorityAction {
  companion object {
    private val presentableFamilyName = CodeInsightBundle.message("inlay.hints.intention.family.name")
  }
  
  private var lastOptionName = ""
  
  override fun getText(): String = getIntentionText()

  private fun getIntentionText(): String {
    if (lastOptionName.startsWith("show", ignoreCase = true)) {
      return "Do not ${lastOptionName.toLowerCase()}"
    }
    return CodeInsightBundle.message("inlay.hints.disable.custom.option", lastOptionName)
  }
  
  override fun getFamilyName(): String = presentableFamilyName

  override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
    InlayParameterHintsExtension.forLanguage(file.language) ?: return false
    if (!hasEditorParameterHintAtOffset(editor, file)) return false
    
    val option = getOptionHintAtOffset(editor, file) ?: return false
    lastOptionName = option.optionName
    
    return true 
  }

  private fun getOptionHintAtOffset(editor: Editor, file: PsiFile): HintInfo.OptionInfo? {
    val offset = editor.caretModel.offset
    return getHintInfoFromProvider(offset, file, editor) as? HintInfo.OptionInfo
  }

  override fun invoke(project: Project, editor: Editor, file: PsiFile) {
    val option = getOptionHintAtOffset(editor, file) ?: return
    option.disable()
  }

  override fun startInWriteAction() = false
}


class ToggleInlineHintsAction : AnAction() {
  
  companion object {
    private val disableText = CodeInsightBundle.message("inlay.hints.disable.action.text").capitalize()
    private val enableText = CodeInsightBundle.message("inlay.hints.enable.action.text").capitalize()
  }
  
  override fun update(e: AnActionEvent) {
    if (!InlayParameterHintsExtension.hasAnyExtensions()) {
      e.presentation.isEnabledAndVisible = false
      return
    }
    
    val isHintsShownNow = EditorSettingsExternalizable.getInstance().isShowParameterNameHints
    e.presentation.text = if (isHintsShownNow) disableText else enableText
    e.presentation.isEnabledAndVisible = true
    
    if (isInMainEditorPopup(e)) {
      val file = CommonDataKeys.PSI_FILE.getData(e.dataContext) ?: return
      val editor = CommonDataKeys.EDITOR.getData(e.dataContext) ?: return
      val caretOffset = editor.caretModel.offset
      e.presentation.isEnabledAndVisible = !isHintsShownNow && isPossibleHintNearOffset(file, caretOffset)
    }
  }

  private fun isInMainEditorPopup(e: AnActionEvent): Boolean {
    if (e.place != ActionPlaces.EDITOR_POPUP) return false
    
    val editor = CommonDataKeys.EDITOR.getData(e.dataContext) ?: return false
    val offset = editor.caretModel.offset
    
    return !editor.inlayModel.hasInlineElementAt(offset)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val settings = EditorSettingsExternalizable.getInstance()
    val before = settings.isShowParameterNameHints
    settings.isShowParameterNameHints = !before

    refreshAllOpenEditors()
  }
}


private fun hasEditorParameterHintAtOffset(editor: Editor, file: PsiFile): Boolean {
  if (editor is EditorWindow) return false
  
  val offset = editor.caretModel.offset
  val element = file.findElementAt(offset)
  
  val startOffset = element?.textRange?.startOffset ?: offset
  val endOffset = element?.textRange?.endOffset ?: offset
  
  return editor.inlayModel
      .getInlineElementsInRange(startOffset, endOffset)
      .find { ParameterHintsPresentationManager.getInstance().isParameterHint(it) } != null
}


private fun refreshAllOpenEditors() {
  ProjectManager.getInstance().openProjects.forEach {
    val psiManager = PsiManager.getInstance(it)
    val daemonCodeAnalyzer = DaemonCodeAnalyzer.getInstance(it)
    val fileEditorManager = FileEditorManager.getInstance(it)

    fileEditorManager.selectedFiles.forEach {
      psiManager.findFile(it)?.let { daemonCodeAnalyzer.restart(it) }
    }
  }
}


private fun getHintInfoFromProvider(offset: Int, file: PsiFile, editor: Editor): HintInfo? {
  val element = file.findElementAt(offset) ?: return null
  val provider = InlayParameterHintsExtension.forLanguage(file.language) ?: return null
  
  val isHintOwnedByElement: (PsiElement) -> Boolean = { e -> provider.getHintInfo(e) != null && e.isOwnsInlayInEditor(editor) }
  val method = PsiTreeUtil.findFirstParent(element, isHintOwnedByElement) ?: return null
  
  return provider.getHintInfo(method)
}


fun PsiElement.isOwnsInlayInEditor(editor: Editor): Boolean {
  if (textRange == null) return false
  val start = if (textRange.isEmpty) textRange.startOffset else textRange.startOffset + 1
  return !editor.inlayModel.getInlineElementsInRange(start, textRange.endOffset).isEmpty()
}

fun isPossibleHintNearOffset(file: PsiFile, offset: Int): Boolean {
  val hintProvider = InlayParameterHintsExtension.forLanguage(file.language) ?: return false

  var element = file.findElementAt(offset)
  for (i in 0..3) {
    if (element == null) return false

    val hints = hintProvider.getParameterHints(element)
    if (hints.isNotEmpty()) return true
    element = element.parent
  }

  return false
}


fun MethodInfo.toPattern() = this.fullyQualifiedName + '(' + this.paramNames.joinToString(",") + ')'


private fun String.capitalize() = StringUtil.capitalizeWords(this, true)