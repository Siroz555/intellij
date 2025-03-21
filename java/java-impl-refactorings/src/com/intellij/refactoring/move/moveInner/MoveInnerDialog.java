// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.refactoring.move.moveInner;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.java.refactoring.JavaRefactoringBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.JavaProjectRootsUtil;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.NullableComputable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.codeStyle.SuggestedNameInfo;
import com.intellij.psi.codeStyle.VariableKind;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.refactoring.HelpID;
import com.intellij.refactoring.PackageWrapper;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.move.MoveDialogBase;
import com.intellij.refactoring.move.MoveInstanceMembersUtil;
import com.intellij.refactoring.ui.NameSuggestionsField;
import com.intellij.refactoring.ui.PackageNameReferenceEditorCombo;
import com.intellij.refactoring.util.CommonMoveClassesOrPackagesUtil;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import com.intellij.refactoring.util.RefactoringMessageUtil;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.RecentsManager;
import com.intellij.util.CommonJavaRefactoringUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class MoveInnerDialog extends MoveDialogBase {
  private final PsiClass myInnerClass;
  private final PsiElement myTargetContainer;
  private final MoveInnerProcessor myProcessor;

  private EditorTextField myClassNameField;
  private NameSuggestionsField myParameterField;
  private JCheckBox myCbPassOuterClass;
  private JPanel myPanel;
  private JCheckBox myCbSearchInComments;
  private JCheckBox myCbSearchForTextOccurences;
  private PackageNameReferenceEditorCombo myPackageNameField;
  private JLabel myPackageNameLabel;
  private JLabel myClassNameLabel;
  private JLabel myParameterNameLabel;
  private SuggestedNameInfo mySuggestedNameInfo;
  private final PsiClass myOuterClass;

  private static final @NonNls String RECENTS_KEY = "MoveInnerDialog.RECENTS_KEY";

  @Override
  protected @NotNull String getRefactoringId() {
    return "MoveInner";
  }

  public MoveInnerDialog(Project project, PsiClass innerClass, MoveInnerProcessor processor, final PsiElement targetContainer) {
    super(project, true, true);
    myInnerClass = innerClass;
    myTargetContainer = targetContainer;
    myOuterClass = myInnerClass.getContainingClass();
    myProcessor = processor;
    setTitle(MoveInnerImpl.getRefactoringName());
    init();
    myPackageNameLabel.setLabelFor(myPackageNameField.getChildComponent());
    myClassNameLabel.setLabelFor(myClassNameField);
    myParameterNameLabel.setLabelFor(myParameterField);
  }

  public boolean isSearchInComments() {
    return myCbSearchInComments.isSelected();
  }

  public boolean isSearchInNonJavaFiles() {
    return myCbSearchForTextOccurences.isSelected();
  }

  public @NotNull String getClassName() {
    return myClassNameField.getText().trim();
  }

  public @Nullable String getParameterName() {
    if (myParameterField != null) {
      return myParameterField.getEnteredName();
    }
    else {
      return null;
    }
  }

  public boolean isPassOuterClass() {
    return myCbPassOuterClass.isSelected();
  }

  public @NotNull PsiClass getInnerClass() {
    return myInnerClass;
  }

  @Override
  protected void init() {
    myClassNameField.setText(myInnerClass.getName());
    myClassNameField.selectAll();

    if (!myInnerClass.hasModifierProperty(PsiModifier.STATIC)) {
      myCbPassOuterClass.setSelected(true);
      myCbPassOuterClass.addItemListener(new ItemListener() {
        @Override
        public void itemStateChanged(ItemEvent e) {
          myParameterField.setEnabled(myCbPassOuterClass.isSelected());
        }
      });
    }
    else {
      myCbPassOuterClass.setSelected(false);
      myCbPassOuterClass.setEnabled(false);
      myParameterField.setEnabled(false);
    }

    if (myCbPassOuterClass.isEnabled()) {
      boolean thisNeeded = isThisNeeded(myInnerClass, myOuterClass);
      myCbPassOuterClass.setSelected(thisNeeded);
      myParameterField.setEnabled(thisNeeded);
    }

    myCbPassOuterClass.addItemListener(new ItemListener() {
      @Override
      public void itemStateChanged(ItemEvent e) {
        boolean selected = myCbPassOuterClass.isSelected();
        myParameterField.getComponent().setEnabled(selected);
      }
    });

    if (!(myTargetContainer instanceof PsiDirectory)) {
      myPackageNameField.setVisible(false);
      myPackageNameLabel.setVisible(false);
    }

    super.init();
  }

  public static boolean isThisNeeded(final PsiClass innerClass, final PsiClass outerClass) {
    final Map<PsiClass, Set<PsiMember>> classesToMembers = MoveInstanceMembersUtil.getThisClassesToMembers(innerClass);
    for (PsiClass psiClass : classesToMembers.keySet()) {
      if (InheritanceUtil.isInheritorOrSelf(outerClass, psiClass, true)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myClassNameField;
  }

  @Override
  protected String getDimensionServiceKey() {
    return "#com.intellij.refactoring.move.moveInner.MoveInnerDialog";
  }

  @Override
  protected JComponent createNorthPanel() {
    return myPanel;
  }

  @Override
  protected JComponent createCenterPanel() {
    return null;
  }

  private @Nullable PsiElement getTargetContainer() {
    if (myTargetContainer instanceof PsiDirectory psiDirectory) {
      PsiPackage oldPackage = getTargetPackage();
      String name = oldPackage == null ? "" : oldPackage.getQualifiedName();
      final String targetName = getPackageName();
      if (!Objects.equals(name, targetName)) {
        final ProjectRootManager projectRootManager = ProjectRootManager.getInstance(myProject);
        final List<VirtualFile> contentSourceRoots = JavaProjectRootsUtil.getSuitableDestinationSourceRoots(myProject);
        final PackageWrapper newPackage = new PackageWrapper(PsiManager.getInstance(myProject), targetName);
        final VirtualFile targetSourceRoot;
        if (contentSourceRoots.size() > 1) {
          PsiPackage targetPackage = JavaPsiFacade.getInstance(myProject).findPackage(targetName);
          PsiDirectory initialDir = null;
          if (targetPackage != null) {
            final PsiDirectory[] directories = targetPackage.getDirectories();
            final VirtualFile root = projectRootManager.getFileIndex().getSourceRootForFile(psiDirectory.getVirtualFile());
            for(PsiDirectory dir: directories) {
              if (Comparing.equal(projectRootManager.getFileIndex().getSourceRootForFile(dir.getVirtualFile()), root)) {
                initialDir = dir;
                break;
              }
            }
          }
          final VirtualFile sourceRoot = CommonMoveClassesOrPackagesUtil.chooseSourceRoot(newPackage, contentSourceRoots, initialDir);
          if (sourceRoot == null) return null;
          targetSourceRoot = sourceRoot;
        }
        else {
          targetSourceRoot = contentSourceRoots.get(0);
        }
        PsiDirectory dir = CommonJavaRefactoringUtil.findPackageDirectoryInSourceRoot(newPackage, targetSourceRoot);
        if (dir == null) {
          dir = ApplicationManager.getApplication().runWriteAction((NullableComputable<PsiDirectory>)() -> {
            try {
              return CommonJavaRefactoringUtil.createPackageDirectoryInSourceRoot(newPackage, targetSourceRoot);
            }
            catch (IncorrectOperationException e) {
              return null;
            }
          });
        }
        return dir;
      }
    }
    return myTargetContainer;
  }

  @Override
  protected void doAction() {
    String message = null;
    final String className = getClassName();
    final String parameterName = getParameterName();
    PsiManager manager = PsiManager.getInstance(myProject);
    if (className.isEmpty()) {
      message = JavaRefactoringBundle.message("no.class.name.specified");
    }
    else {
      if (!PsiNameHelper.getInstance(manager.getProject()).isIdentifier(className)) {
        message = RefactoringMessageUtil.getIncorrectIdentifierMessage(className);
      }
      else {
        if (myCbPassOuterClass.isSelected()) {
          if (parameterName != null && parameterName.isEmpty()) {
            message = JavaRefactoringBundle.message("no.parameter.name.specified");
          }
          else {
            if (!PsiNameHelper.getInstance(manager.getProject()).isIdentifier(parameterName)) {
              message = RefactoringMessageUtil.getIncorrectIdentifierMessage(parameterName);
            }
          }
        }
        if (message == null && myTargetContainer instanceof PsiClass targetClass) {
          PsiClass[] classes = targetClass.getInnerClasses();
          for (PsiClass aClass : classes) {
            if (className.equals(aClass.getName())) {
              message = JavaRefactoringBundle.message("inner.class.exists", className, targetClass.getName());
              break;
            }
          }
        }
      }
    }

    PsiElement target = null;

    if (message == null) {
      if (myCbPassOuterClass.isSelected() && mySuggestedNameInfo != null) {
        mySuggestedNameInfo.nameChosen(getParameterName());
      }

      target = getTargetContainer();
      if (target == null) return;

      if (target instanceof PsiDirectory) {
        message = RefactoringMessageUtil.checkCanCreateClass((PsiDirectory)target, className);

        if (message == null) {
          final String packageName = getPackageName();
          if (!packageName.isEmpty() && !PsiNameHelper.getInstance(myProject).isQualifiedName(packageName)) {
            message = RefactoringMessageUtil.getIncorrectIdentifierMessage(packageName);
          }
        }
      }
    }

    if (message != null) {
      CommonRefactoringUtil.showErrorMessage(
        MoveInnerImpl.getRefactoringName(),
        message,
        HelpID.MOVE_INNER_UPPER,
        myProject);
      return;
    }

    RecentsManager.getInstance(myProject).registerRecentEntry(RECENTS_KEY, getPackageName());
    myProcessor.setup(getInnerClass(), className, isPassOuterClass(), parameterName,
                      isSearchInComments(), isSearchInNonJavaFiles(), target);

    final boolean openInEditor = isOpenInEditor();
    myProcessor.setOpenInEditor(openInEditor);
    invokeRefactoring(myProcessor);
  }

  private String getPackageName() {
    return myPackageNameField.getText().trim();
  }

  @Override
  protected String getHelpId() {
    return HelpID.MOVE_INNER_UPPER;
  }

  private void createUIComponents() {
    if (!myInnerClass.hasModifierProperty(PsiModifier.STATIC)) {
      final PsiManager manager = myInnerClass.getManager();
      PsiType outerType = JavaPsiFacade.getElementFactory(manager.getProject()).createType(myInnerClass.getContainingClass());
      mySuggestedNameInfo =  JavaCodeStyleManager.getInstance(myProject).suggestVariableName(VariableKind.PARAMETER, null, null, outerType);
      String[] variants = mySuggestedNameInfo.names;
      myParameterField = new NameSuggestionsField(variants, myProject, JavaFileType.INSTANCE);
    }
    else {
      myParameterField = new NameSuggestionsField(new String[]{""}, myProject, JavaFileType.INSTANCE);
      myParameterField.getComponent().setEnabled(false);
    }

    PsiPackage psiPackage = getTargetPackage();
    myPackageNameField = new PackageNameReferenceEditorCombo(psiPackage != null ? psiPackage.getQualifiedName() : "", myProject, RECENTS_KEY,
                                                             RefactoringBundle.message("choose.destination.package"));
  }

  private @Nullable PsiPackage getTargetPackage() {
    if (myTargetContainer instanceof PsiDirectory directory) {
      return JavaDirectoryService.getInstance().getPackage(directory);
    }
    return null;
  }
}