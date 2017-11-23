/*******************************************************************************
 * Copyright (c) 2017 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Microsoft Corporation - initial API and implementation
 *******************************************************************************/

package com.microsoft.java.debug.plugin.internal.eval;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.ISourceLocator;
import org.eclipse.debug.core.sourcelookup.AbstractSourceLookupDirector;
import org.eclipse.debug.core.sourcelookup.containers.ProjectSourceContainer;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.debug.eval.ICompiledExpression;
import org.eclipse.jdt.internal.debug.core.model.JDIDebugTarget;
import org.eclipse.jdt.internal.debug.core.model.JDIStackFrame;
import org.eclipse.jdt.internal.debug.core.model.JDIThread;
import org.eclipse.jdt.internal.debug.eval.ast.engine.ASTEvaluationEngine;
import org.eclipse.jdt.internal.launching.JavaSourceLookupDirector;

import com.microsoft.java.debug.core.Configuration;
import com.microsoft.java.debug.core.adapter.IEvaluationListener;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

public class JdtEvaluationProvider implements IEvaluationProvider {
    public static final String DEFAULT_PROJECT_NAME = "jdt.ls-java-project";
    private IJavaProject project;
    private ILaunch launch;
    private JDIDebugTarget debugTarget;
    private Map<ThreadReference, JDIThread> threadMap = new HashMap<>();
    private static final Logger logger = Logger.getLogger(Configuration.LOGGER_NAME);

    @Override
    public void eval(String projectName, String code, StackFrame sf, IEvaluationListener listener) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        projectName = StringUtils.isBlank(projectName) ? DEFAULT_PROJECT_NAME : projectName;
        if (project == null) {
            for (IProject proj : root.getProjects()) {
                try {
                    if (proj.isNatureEnabled("org.eclipse.jdt.core.javanature") && proj.getName().equals(projectName)) {
                        project = JavaCore.create(proj);
                        break;
                    }
                } catch (CoreException e) {
                    logger.severe(String.format("Cannot initialize project: %s", e.toString()));
                }
            }
        }

        if (project == null) {
            throw new IllegalStateException(String.format("Project %s cannot be found.", projectName));
        }

        if (launch == null) {
            launch = createLaunch(project);
        }
        ThreadReference thread = sf.thread();
        if (debugTarget == null) {
            debugTarget = new JDIDebugTarget(launch, thread.virtualMachine(), "", false, false, null, false) {
                @Override
                protected synchronized void initialize() {

                }
            };
        }
        try {
            JDIThread jdiThread = getJDIThread(thread);

            synchronized (jdiThread) {
                if (jdiThread.isPerformingEvaluation()) {
                    jdiThread.wait();
                }

                ASTEvaluationEngine engine = new ASTEvaluationEngine(project, debugTarget);
                JDIStackFrame stackframe = createStackFrame(sf);

                ICompiledExpression ie = engine.getCompiledExpression(code, stackframe);
                engine.evaluateExpression(ie, stackframe, evaluateResult -> {
                    synchronized (jdiThread) {
                        jdiThread.notify();
                    }
                    if (evaluateResult == null || evaluateResult.hasErrors()) {
                        listener.evaluationComplete(null, evaluateResult.getException() != null ? evaluateResult.getException()
                                : new RuntimeException(StringUtils.join(evaluateResult.getErrorMessages())));
                    } else {
                        try {
                            Value value = (Value) FieldUtils.readField(evaluateResult.getValue(), "fValue", true);
                            listener.evaluationComplete(value, null);
                        } catch (IllegalArgumentException e) {
                            listener.evaluationComplete(null, e);
                        } catch (IllegalAccessException e) {
                            listener.evaluationComplete(null, e);
                        }
                    }
                }, 0, false);
            }

        } catch (CoreException e) {
            listener.evaluationComplete(null, e);
        } catch (Exception e) {
            listener.evaluationComplete(null, e);
        }
    }

    private JDIStackFrame createStackFrame(StackFrame sf) {
        return new JDIStackFrame(getJDIThread(sf.thread()), sf, 0);
    }

    private JDIThread getJDIThread(ThreadReference thread) {
        synchronized (threadMap) {
            return threadMap.computeIfAbsent(thread, threadKey -> new JDIThread(debugTarget, thread)
            );
        }

    }

    @Override
    public boolean isInEvaluation(ThreadReference thread) {
        return debugTarget != null && getJDIThread(thread).isPerformingEvaluation();
    }

    private static ILaunch createLaunch(IJavaProject project) {
        return new ILaunch() {

            private AbstractSourceLookupDirector locator;

            @Override
            public boolean canTerminate() {

                return false;
            }

            @Override
            public boolean isTerminated() {

                return false;
            }

            @Override
            public void terminate() throws DebugException {

            }

            @Override
            public <T> T getAdapter(Class<T> arg0) {

                return null;
            }

            @Override
            public void addDebugTarget(IDebugTarget arg0) {

            }

            @Override
            public void addProcess(IProcess arg0) {

            }

            @Override
            public String getAttribute(String arg0) {

                return null;
            }

            @Override
            public Object[] getChildren() {

                return null;
            }

            @Override
            public IDebugTarget getDebugTarget() {

                return null;
            }

            @Override
            public IDebugTarget[] getDebugTargets() {

                return null;
            }

            @Override
            public ILaunchConfiguration getLaunchConfiguration() {

                return null;
            }

            @Override
            public String getLaunchMode() {

                return null;
            }

            @Override
            public IProcess[] getProcesses() {

                return null;
            }

            @Override
            public ISourceLocator getSourceLocator() {
                if (locator != null) {
                    return locator;
                }
                locator = new JavaSourceLookupDirector();

                try {
                    locator.setSourceContainers(new ProjectSourceContainer(project.getProject(), true).getSourceContainers());
                } catch (CoreException e) {
                    logger.severe(String.format("Cannot initialize JavaSourceLookupDirector: %s", e.toString()));
                }
                locator.initializeParticipants();
                return locator;
            }

            @Override
            public boolean hasChildren() {

                return false;
            }

            @Override
            public void removeDebugTarget(IDebugTarget arg0) {

            }

            @Override
            public void removeProcess(IProcess arg0) {

            }

            @Override
            public void setAttribute(String arg0, String arg1) {

            }

            @Override
            public void setSourceLocator(ISourceLocator arg0) {

            }

        };
    }

}
