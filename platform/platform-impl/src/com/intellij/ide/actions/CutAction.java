// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.actions;

import com.intellij.ide.CutProvider;
import com.intellij.ide.lightEdit.LightEditCompatible;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class CutAction extends DumbAwareAction implements LightEditCompatible {
  public CutAction() {
    setEnabledInModalContext(true);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    CutProvider provider = getAvailableCutProvider(e);
    if (provider == null) {
      return;
    }
    provider.performCut(e.getDataContext());
  }

  private static CutProvider getAvailableCutProvider(@NotNull AnActionEvent e) {
    CutProvider provider = e.getData(PlatformDataKeys.CUT_PROVIDER);
    Project project = e.getProject();
    if (project != null && DumbService.isDumb(project) && !DumbService.isDumbAware(provider)) {
      return null;
    }
    return provider;
  }

  @Override
  public void update(@NotNull AnActionEvent event) {
    CopyAction.updateFromProvider(event, PlatformDataKeys.CUT_PROVIDER, (provider, presentation) -> {
      DataContext dataContext = event.getDataContext();
      Project project = CommonDataKeys.PROJECT.getData(dataContext);
      boolean notDumbAware = project != null && DumbService.isDumb(project) && !DumbService.isDumbAware(provider);
      presentation.setEnabled(!notDumbAware && project != null && project.isOpen() && provider != null && provider.isCutEnabled(dataContext));
      if (event.getPlace().equals(ActionPlaces.EDITOR_POPUP) && provider != null) {
        presentation.setVisible(provider.isCutVisible(dataContext));
      }
      else {
        presentation.setVisible(true);
      }
    });
  }
}
