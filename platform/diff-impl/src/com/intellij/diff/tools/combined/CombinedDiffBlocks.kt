// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.diff.tools.combined

import com.intellij.diff.FrameDiffTool
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.SideBorder
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.CheckBox
import com.intellij.ui.components.panels.OpaquePanel
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.FontUtil
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

interface CombinedBlockId

interface CombinedDiffBlock<ID: CombinedBlockId> : Disposable {
  val id: ID

  val header: JComponent
  val body: JComponent
  val component: JComponent

  fun updateBlockContent(newContent: CombinedDiffBlockContent) {}
}

interface CombinedDiffGlobalBlockHeaderProvider {
  val globalHeader: JComponent
}

class CombinedDiffBlockContent(val viewer: FrameDiffTool.DiffViewer, val blockId: CombinedBlockId)

interface CombinedDiffBlockFactory<ID: CombinedBlockId> {
  companion object {
    private val EP_COMBINED_DIFF_BLOCK_FACTORY =
      ExtensionPointName<CombinedDiffBlockFactory<*>>("com.intellij.diff.tools.combined.diffBlockFactory")

    @Suppress("UNCHECKED_CAST")
    fun <ID: CombinedBlockId> findApplicable(content: CombinedDiffBlockContent): CombinedDiffBlockFactory<ID>? {
      return EP_COMBINED_DIFF_BLOCK_FACTORY.findFirstSafe { it.isApplicable(content) } as? CombinedDiffBlockFactory<ID>
    }
  }

  fun isApplicable(content: CombinedDiffBlockContent): Boolean
  fun createBlock(content: CombinedDiffBlockContent, withBorder: Boolean): CombinedDiffBlock<ID>
}

class CombinedSimpleDiffBlockFactory : CombinedDiffBlockFactory<CombinedPathBlockId> {
  override fun isApplicable(content: CombinedDiffBlockContent) = true //default factory
  override fun createBlock(content: CombinedDiffBlockContent, withBorder: Boolean): CombinedDiffBlock<CombinedPathBlockId> =
    with(content.blockId as CombinedPathBlockId) { CombinedSimpleDiffBlock(this, content.viewer.component, withBorder) }
}

private class CombinedSimpleDiffHeader(blockId: CombinedPathBlockId, withBorder: Boolean, withPathOnly: Boolean) : BorderLayoutPanel() {
  init {
    if (withBorder) {
      border = IdeBorderFactory.createBorder(SideBorder.TOP)
    }

    addToCenter(if (withPathOnly) buildPathComponent(blockId) else buildToolbar(blockId).component)
  }

  private fun buildPathComponent(blockId: CombinedPathBlockId): JComponent {
    background = UIUtil.getListBackground()
    return OpaquePanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0))
      .apply {
        border = JBEmptyBorder(UIUtil.PANEL_SMALL_INSETS)
        add(createTextComponent(blockId.path))
      }
  }

  private fun buildToolbar(blockId: CombinedPathBlockId): ActionToolbar {
    val path = blockId.path
    val toolbarGroup = DefaultActionGroup()
    toolbarGroup.add(CombinedOpenInEditorAction(path))
    toolbarGroup.addSeparator()
    toolbarGroup.add(SelectableFilePathLabel(path))

    val toolbar = ActionManager.getInstance().createActionToolbar("CombinedDiffBlockHeaderToolbar", toolbarGroup, true)
    toolbar.targetComponent = this
    toolbar.layoutPolicy = ActionToolbar.NOWRAP_LAYOUT_POLICY
    toolbar.component.background = UIUtil.getListBackground()
    toolbar.component.border = JBEmptyBorder(UIUtil.PANEL_SMALL_INSETS)
    toolbarGroup.add(CombinedPrevNextFileAction(blockId, toolbar.component, false))
    toolbarGroup.add(CombinedPrevNextFileAction(blockId, toolbar.component, true))

    return toolbar
  }

  private class SelectableFilePathLabel(private val path: FilePath) : DumbAwareAction(), CustomComponentAction {

    private val checkBox = CheckBox("").apply { background = UIUtil.getListBackground() }

    var selected: Boolean
      get() = checkBox.isSelected
      set(value) { checkBox.isSelected = value }

    fun setSelectable(selectable: Boolean) {
      checkBox.isVisible = selectable
    }

    init {
      selected = false
      setSelectable(false)
    }

    override fun actionPerformed(e: AnActionEvent) {}

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
      val textComponent = createTextComponent(path)
      val component = OpaquePanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0))
        .apply {
          add(checkBox)
          add(textComponent)
        }

      return component
    }
  }

  companion object {
    private fun createTextComponent(path: FilePath): JComponent {
      return SimpleColoredComponent().append(path.name)
        .apply {
          val parentPath = path.parentPath?.let(FilePath::getPresentableUrl)?.let(FileUtil::getLocationRelativeToUserHome)
          if (parentPath != null) {
            append(FontUtil.spaceAndThinSpace() + parentPath, SimpleTextAttributes.GRAYED_ATTRIBUTES)
          }
          icon = FileTypeRegistry.getInstance().getFileTypeByFileName(path.name).icon
        }
    }
  }
}

data class CombinedPathBlockId(val path: FilePath, val fileStatus: FileStatus, val tag: Any? = null) : CombinedBlockId

private class CombinedSimpleDiffBlock(override val id: CombinedPathBlockId, initialContent: JComponent, notFirstBlock: Boolean) :
  JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, true)),
  CombinedDiffBlock<CombinedPathBlockId>, CombinedDiffGlobalBlockHeaderProvider {

  private val pathOnlyHeader = CombinedSimpleDiffHeader(id, notFirstBlock, true)
  private val headerWithToolbar = CombinedSimpleDiffHeader(id, notFirstBlock, false)

  override val header = Wrapper(pathOnlyHeader)
  override val globalHeader = if (notFirstBlock) CombinedSimpleDiffHeader(id, false, false) else headerWithToolbar
  override val body = Wrapper(initialContent)

  init {
    if (notFirstBlock) {
      add(header)
    }
    add(body)
  }

  override fun updateBlockContent(newContent: CombinedDiffBlockContent) {
    val viewer = newContent.viewer
    body.setContent(viewer.component)
    header.setContent(if (viewer is CombinedLazyDiffViewer) pathOnlyHeader else headerWithToolbar)
  }

  override val component = this
  override fun dispose() {}
}
