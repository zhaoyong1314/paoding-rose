/*
* Copyright 2007-2009 the original author or authors.
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
package net.paoding.rose.web.impl.thread;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.paoding.rose.web.Dispatcher;
import net.paoding.rose.web.Invocation;
import net.paoding.rose.web.InvocationUtils;
import net.paoding.rose.web.RequestPath;
import net.paoding.rose.web.annotation.ReqMethod;
import net.paoding.rose.web.impl.mapping.MappingNode;
import net.paoding.rose.web.impl.mapping.MatchResult;
import net.paoding.rose.web.impl.mapping.WebResource;
import net.paoding.rose.web.impl.module.Module;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * 
 * @author 王志亮 [qieqie.wang@gmail.com]
 * 
 */
public class Rose implements EngineChain {

    protected static final Log logger = LogFactory.getLog(Rose.class);

    private List<Module> modules;

    private InvocationBean inv;

    private ArrayList<MatchResult> matchResults;

    private MappingNode mappingTree;

    private int nextIndexOfChain = 0;

    private LinkedList<AfterCompletion> afterCompletions = new LinkedList<AfterCompletion>();

    private RequestPath path;

    private HttpServletRequest originalHttpRequest;

    private HttpServletResponse originalHttpResponse;

    public Rose(List<Module> modules, MappingNode mappingTree, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse, RequestPath requestPath) {
        this.mappingTree = mappingTree;
        this.modules = modules;
        this.originalHttpRequest = httpRequest;
        this.originalHttpResponse = httpResponse;
        this.path = requestPath;
    }

    public MappingNode getMappingTree() {
        return mappingTree;
    }

    public InvocationBean getInvocation() {
        return inv;
    }

    public List<Module> getModules() {
        return modules;
    }

    public List<MatchResult> getMatchResults() {
        return matchResults;
    }

    /**
     * 启动rose逻辑，对请求进行匹配判断，如果匹配未能成功返回false； <br>
     * 对匹配成功的启动相关的架构处理逻辑直至整个请求的完成
     * 
     * @return
     * @throws Throwable
     */
    public boolean start() throws Throwable {
        return innerStart();
    }

    @Override
    public Object doNext() throws Throwable {
        ReqMethod method = inv.getRequestPath().getMethod();
        MatchResult matchResult = matchResults.get(nextIndexOfChain++);
        WebResource resource = matchResult.getResource();
        Engine engine = resource.getEngine(method);
        return engine.execute(this, matchResult);
    }

    private boolean innerStart() throws Throwable {
        ArrayList<MatchResult> matchResults = mappingTree.match(this.path);
        if (matchResults.size() == 0) {
            return false;
        }
        MatchResult mr = matchResults.get(matchResults.size() - 1);
        if (!mr.getResource().isEndResource()) {
            return false;
        }
        if (!mr.getResource().isMethodAllowed(this.path.getMethod())) {
            /* 405 Method Not Allowed
             * The method specified in the Request-Line is not allowed for the
             * resource identified by the Request-URI. The response MUST include an
             * Allow header containing a list of valid methods for the requested
             * resource.
             */
            StringBuilder allow = new StringBuilder();
            final String gap = ", ";
            for (ReqMethod method : mr.getResource().getAllowedMethods()) {
                allow.append(method.toString()).append(gap);
            }
            if (allow.length() > 0) {
                allow.setLength(allow.length() - gap.length());
            }
            originalHttpResponse.addHeader("Allow", allow.toString());
            originalHttpResponse.sendError(405, this.path.getUri());
        } else {
            //
            this.matchResults = matchResults;
            Map<String, String> mrParameters = null;
            for (int i = 0; i < this.matchResults.size(); i++) {
                MatchResult tmr = this.matchResults.get(i);
                if (tmr.getParameterCount() > 0) {
                    if (mrParameters == null) {
                        mrParameters = new HashMap<String, String>(6);
                    }
                    for (String name : tmr.getParameterNames()) {
                        mrParameters.put(name, tmr.getParameter(name));
                    }
                }
            }
            HttpServletRequest httpRequest = originalHttpRequest;
            if (mrParameters != null && mrParameters.size() > 0) {
                httpRequest = new ParameteredUriRequest(originalHttpRequest, mrParameters);
            }

            //
            HttpServletRequest originalThreadHttpRequest = null;
            Invocation preInvocation = null;
            if (path.getDispatcher() != Dispatcher.REQUEST) {
                originalThreadHttpRequest = InvocationUtils.getCurrentThreadRequest();
                if (originalThreadHttpRequest != null) {
                    preInvocation = InvocationUtils.getInvocation(originalThreadHttpRequest);
                    assert preInvocation != null;
                }
            }
            // invocation 对象 代表一次Rose调用
            InvocationBean inv = new InvocationBean(httpRequest, originalHttpResponse, path);
            inv.setRose(this);
            inv.setPreInvocation(preInvocation);
            //
            InvocationUtils.bindRequestToCurrentThread(httpRequest);
            InvocationUtils.bindInvocationToRequest(inv, httpRequest);

            // invoke the engine chain
            this.inv = inv;
            Throwable error = null;
            try {
                ((EngineChain) this).doNext();
            } catch (Throwable local) {
                error = local;
                throw local;
            } finally {
                for (AfterCompletion task : afterCompletions) {
                    try {
                        task.afterCompletion(inv, error);
                    } catch (Exception e) {
                        logger.error("", e);
                    }
                }
                //
                if (originalThreadHttpRequest != null) {
                    InvocationUtils.bindRequestToCurrentThread(originalThreadHttpRequest);
                    InvocationUtils.bindInvocationToRequest(preInvocation,
                            originalThreadHttpRequest);
                } else {
                    InvocationUtils.unindRequestFromCurrentThread();
                }
            }
        }

        return true;
    }

    @Override
    public void addAfterCompletion(AfterCompletion task) {
        afterCompletions.addFirst(task);
    }
}