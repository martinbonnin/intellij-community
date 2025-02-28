// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.debugger.test;

import com.google.common.collect.Lists;
import com.intellij.debugger.NoDataException;
import com.intellij.debugger.PositionManager;
import com.intellij.debugger.SourcePosition;
import com.intellij.debugger.engine.DebugProcess;
import com.intellij.debugger.engine.DebugProcessEvents;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.jdi.VirtualMachineProxyImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.RunAll;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import kotlin.collections.CollectionsKt;
import kotlin.io.FilesKt;
import kotlin.sequences.SequencesKt;
import kotlin.text.StringsKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.backend.common.output.OutputFileCollection;
import org.jetbrains.kotlin.idea.checkers.CompilerTestLanguageVersionSettings;
import org.jetbrains.kotlin.codegen.ClassBuilderFactories;
import org.jetbrains.kotlin.idea.codegen.GenerationUtils;
import org.jetbrains.kotlin.codegen.state.GenerationState;
import org.jetbrains.kotlin.config.*;
import org.jetbrains.kotlin.idea.debugger.KotlinPositionManager;
import org.jetbrains.kotlin.idea.debugger.KotlinPositionManagerFactory;
import org.jetbrains.kotlin.idea.debugger.test.mock.MockLocation;
import org.jetbrains.kotlin.idea.debugger.test.mock.MockVirtualMachine;
import org.jetbrains.kotlin.idea.debugger.test.mock.SmartMockReferenceTypeContext;
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase;
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCaseKt;
import org.jetbrains.kotlin.idea.test.KotlinWithJdkAndRuntimeLightProjectDescriptor;
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.idea.test.ConfigurationKind;
import org.jetbrains.kotlin.idea.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TestJdkKind;
import org.jetbrains.kotlin.utils.ExceptionUtilsKt;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.jetbrains.kotlin.idea.debugger.test.DebuggerTestUtils.DEBUGGER_TESTDATA_PATH_BASE;

public abstract class AbstractPositionManagerTest extends KotlinLightCodeInsightFixtureTestCase {
    // Breakpoint is given as a line comment on a specific line, containing the regexp to match the name of the class where that line
    // can be found. This pattern matches against these line comments and saves the class name in the first group
    private static final Pattern BREAKPOINT_PATTERN = Pattern.compile("^.*//\\s*(.+)\\s*$");

    @Override
    public void setUp() {
        super.setUp();
        myFixture.setTestDataPath(DEBUGGER_TESTDATA_PATH_BASE);
    }

    private DebugProcessImpl debugProcess;

    @NotNull
    @Override
    protected LightProjectDescriptor getProjectDescriptor() {
        return KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE;
    }

    @NotNull
    private static KotlinPositionManager createPositionManager(@NotNull DebugProcess process) {
        KotlinPositionManager positionManager = (KotlinPositionManager) new KotlinPositionManagerFactory().createPositionManager(process);
        assertNotNull(positionManager);
        return positionManager;
    }

    protected void doTest(@NotNull String fileName) {
        String path = getPath(fileName);

        if (fileName.endsWith(".kt")) {
            myFixture.configureByFile(path);
        } else {
            SequencesKt.forEach(FilesKt.walkTopDown(new File(path)), file -> {
                String fileName1 = file.getName();
                String path1 = getPath(fileName1);
                myFixture.configureByFile(path1);
                return null;
            });
        }

        performTest();
    }

    @NotNull
    private static String getPath(@NotNull String fileName) {
        return StringsKt.substringAfter(fileName, DEBUGGER_TESTDATA_PATH_BASE, fileName);
    }

    private void performTest() {
        Project project = getProject();
        List<KtFile> files = new ArrayList<>(KotlinLightCodeInsightFixtureTestCaseKt.allKotlinFiles(project));
        if (files.isEmpty()) return;

        List<Breakpoint> breakpoints = Lists.newArrayList();
        for (KtFile file : files) {
            breakpoints.addAll(extractBreakpointsInfo(file, file.getText()));
        }

        CompilerConfiguration configuration = KotlinTestUtils.newConfiguration(ConfigurationKind.STDLIB, TestJdkKind.MOCK_JDK);
        // TODO: delete this once IDEVirtualFileFinder supports loading .kotlin_builtins files
        CommonConfigurationKeysKt.setLanguageVersionSettings(configuration, new CompilerTestLanguageVersionSettings(
                Collections.emptyMap(),
                ApiVersion.LATEST_STABLE,
                LanguageVersion.LATEST_STABLE,
                Collections.singletonMap(JvmAnalysisFlags.getSuppressMissingBuiltinsError(), true)
        ));

        GenerationState state =
                GenerationUtils.compileFiles(files, configuration, ClassBuilderFactories.TEST, scope -> PackagePartProvider.Empty.INSTANCE);

        Map<String, ReferenceType> referencesByName = getReferenceMap(state.getFactory());

        debugProcess = createDebugProcess(referencesByName);

        PositionManager positionManager = createPositionManager(debugProcess);

        ApplicationManager.getApplication().runReadAction(() -> {
            try {
                for (Breakpoint breakpoint : breakpoints) {
                    assertBreakpointIsHandledCorrectly(breakpoint, positionManager);
                }
            }
            catch (NoDataException e) {
                throw ExceptionUtilsKt.rethrow(e);
            }
        });

    }

    @Override
    public void tearDown() {
        RunAll.runAll(
                () -> {
                    if (debugProcess != null) {
                        debugProcess.stop(true);
                    }
                },
                () -> {
                    if (debugProcess != null) {
                        debugProcess.dispose();
                        debugProcess = null;
                    }
                },
                () -> super.tearDown()
        );
    }

    private static Collection<Breakpoint> extractBreakpointsInfo(KtFile file, String fileContent) {
        Collection<Breakpoint> breakpoints = Lists.newArrayList();
        String[] lines = StringUtil.convertLineSeparators(fileContent).split("\n");

        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = BREAKPOINT_PATTERN.matcher(lines[i]);
            if (matcher.matches()) {
                breakpoints.add(new Breakpoint(file, i, matcher.group(1)));
            }
        }

        return breakpoints;
    }

    private static Map<String, ReferenceType> getReferenceMap(OutputFileCollection outputFiles) {
        return new SmartMockReferenceTypeContext(outputFiles).getReferenceTypesByName();
    }

    private DebugProcessEvents createDebugProcess(Map<String, ReferenceType> referencesByName) {
        return new DebugProcessEvents(getProject()) {
            private VirtualMachineProxyImpl virtualMachineProxy;

            @NotNull
            @Override
            public VirtualMachineProxyImpl getVirtualMachineProxy() {
                if (virtualMachineProxy == null) {
                    virtualMachineProxy = new MockVirtualMachineProxy(this, referencesByName);
                }
                return virtualMachineProxy;
            }

            @NotNull
            @Override
            public GlobalSearchScope getSearchScope() {
                return GlobalSearchScope.allScope(getProject());
            }
        };
    }

    private static void assertBreakpointIsHandledCorrectly(Breakpoint breakpoint, PositionManager positionManager) throws NoDataException {
        SourcePosition position = SourcePosition.createFromLine(breakpoint.file, breakpoint.lineNumber);
        List<ReferenceType> classes = positionManager.getAllClasses(position);
        assertNotNull(classes);
        assertFalse("Classes not found for line " + (breakpoint.lineNumber + 1) + ", expected " + breakpoint.classNameRegexp,
                    classes.isEmpty());

        if (classes.stream().noneMatch(clazz -> clazz.name().matches(breakpoint.classNameRegexp))) {
            throw new AssertionError("Breakpoint class '" + breakpoint.classNameRegexp +
                                     "' from line " + (breakpoint.lineNumber + 1) + " was not found in the PositionManager classes names: " +
                                     classes.stream().map(ReferenceType::name).collect(Collectors.joining(",")));
        }

        ReferenceType typeWithFqName = classes.get(0);
        Location location = new MockLocation(typeWithFqName, breakpoint.file.getName(), breakpoint.lineNumber + 1);

        SourcePosition actualPosition = positionManager.getSourcePosition(location);
        assertNotNull(actualPosition);
        assertEquals(position.getFile(), actualPosition.getFile());
        assertEquals(position.getLine(), actualPosition.getLine());
    }

    private static class Breakpoint {
        private final KtFile file;
        private final int lineNumber; // 0-based
        private final String classNameRegexp;

        private Breakpoint(KtFile file, int lineNumber, String classNameRegexp) {
            this.file = file;
            this.lineNumber = lineNumber;
            this.classNameRegexp = classNameRegexp;
        }
    }

    private static class MockVirtualMachineProxy extends VirtualMachineProxyImpl {
        private final Map<String, ReferenceType> referencesByName;

        private MockVirtualMachineProxy(DebugProcessEvents debugProcess, Map<String, ReferenceType> referencesByName) {
            super(debugProcess, new MockVirtualMachine());
            this.referencesByName = referencesByName;
        }

        @Override
        public List<ReferenceType> allClasses() {
            return new ArrayList<>(referencesByName.values());
        }

        @Override
        public List<ReferenceType> classesByName(@NotNull String name) {
            return CollectionsKt.listOfNotNull(referencesByName.get(name));
        }
    }
}
