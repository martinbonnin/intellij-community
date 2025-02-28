// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.dsl.builder.impl

import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.UiSwitcher
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.Expandable
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.gridLayout.Gaps
import com.intellij.ui.dsl.gridLayout.HorizontalAlign
import org.jetbrains.annotations.ApiStatus
import javax.swing.border.EmptyBorder

@ApiStatus.Internal
internal class CollapsibleRowImpl(dialogPanelConfig: DialogPanelConfig,
                                  panelContext: PanelContext,
                                  parent: PanelImpl,
                                  @NlsContexts.BorderTitle title: String,
                                  init: Panel.() -> Unit) :
  RowImpl(dialogPanelConfig, panelContext, parent, RowLayout.INDEPENDENT), CollapsibleRow {

  private val collapsibleTitledSeparator = CollapsibleTitledSeparatorImpl(title)

  override var expanded by collapsibleTitledSeparator::expanded

  override fun setText(@NlsContexts.Separator text: String) {
    collapsibleTitledSeparator.text = text
  }

  override fun addExpandedListener(action: (Boolean) -> Unit) {
    collapsibleTitledSeparator.expandedProperty.afterChange { action(it) }
  }

  override fun applyToTitleComponent(task: (CollapsibleTitledSeparator) -> Unit): CollapsibleRowImpl {
    task(collapsibleTitledSeparator)
    return this
  }

  init {
    collapsibleTitledSeparator.setLabelFocusable(true)
    (collapsibleTitledSeparator.label.border as? EmptyBorder)?.borderInsets?.let {
      collapsibleTitledSeparator.putClientProperty(DslComponentProperty.VISUAL_PADDINGS,
                                                   Gaps(top = it.top, left = it.left, bottom = it.bottom))
    }

    collapsibleTitledSeparator.label.putClientProperty(Expandable::class.java, object : Expandable {
      override fun expand() {
        expanded = true
      }

      override fun collapse() {
        expanded = false
      }

      override fun isExpanded(): Boolean {
        return expanded
      }
    })

    val action = DumbAwareAction.create { expanded = !expanded }
    action.registerCustomShortcutSet(ActionUtil.getShortcutSet("CollapsiblePanel-toggle"), collapsibleTitledSeparator.label)

    val collapsibleTitledSeparator = this.collapsibleTitledSeparator
    lateinit var expandablePanel: Panel
    panel {
      row {
        cell(collapsibleTitledSeparator).horizontalAlign(HorizontalAlign.FILL)
      }
      expandablePanel = panel {
        init()
      }
      collapsibleTitledSeparator.onAction {
        expandablePanel.visible(it)
      }
    }
    applyUiSwitcher(expandablePanel as PanelImpl, CollapsibleRowUiSwitcher(this))
  }

  private fun applyUiSwitcher(panel: PanelImpl, uiSwitcher: UiSwitcher) {
    for (row in panel.rows) {
      for (cell in row.cells) {
        when (cell) {
          is CellImpl<*> -> UiSwitcher.append(cell.viewComponent, uiSwitcher)
          is PanelImpl -> applyUiSwitcher(cell, uiSwitcher)
          else -> {}
        }
      }
    }
  }

  private class CollapsibleRowUiSwitcher(private val collapsibleRow: CollapsibleRowImpl) : UiSwitcher {

    override fun show(): Boolean {
      collapsibleRow.expanded = true
      return true
    }
  }
}
