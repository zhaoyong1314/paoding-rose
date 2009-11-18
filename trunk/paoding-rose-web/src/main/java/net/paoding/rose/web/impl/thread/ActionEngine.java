/*
 * Copyright 2007-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.paoding.rose.web.impl.thread;

import static org.springframework.validation.BindingResult.MODEL_KEY_PREFIX;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.paoding.rose.web.Invocation;
import net.paoding.rose.web.NamedValidator;
import net.paoding.rose.web.RequestPath;
import net.paoding.rose.web.annotation.HttpFeatures;
import net.paoding.rose.web.annotation.Intercepted;
import net.paoding.rose.web.annotation.Return;
import net.paoding.rose.web.impl.module.Module;
import net.paoding.rose.web.impl.module.NestedControllerInterceptorWrapper;
import net.paoding.rose.web.impl.thread.tree.Rose;
import net.paoding.rose.web.impl.validation.ParameterBindingResult;
import net.paoding.rose.web.paramresolver.MethodParameterResolver;
import net.paoding.rose.web.paramresolver.ParamResolver;
import net.paoding.rose.web.paramresolver.ParameterNameDiscovererImpl;
import net.paoding.rose.web.paramresolver.ResolverFactoryImpl;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.validation.Errors;

/**
 * @author 王志亮 [qieqie.wang@gmail.com]
 */
public final class ActionEngine implements Engine {

    private static Log logger = LogFactory.getLog(ActionEngine.class);

    private final Module module;

    private final Class<?> controllerClass;

    private final Object controller;

    private final Method method;

    private final NestedControllerInterceptorWrapper[] interceptors;

    private final NamedValidator[] validators;

    private final MethodParameterResolver methodParameterResolver;

    private transient String toStringCache;

    public ActionEngine(Module module, Class<?> controllerClass, Object controller, Method method) {
        this.module = module;
        this.controllerClass = controllerClass;
        this.controller = controller;
        this.method = method;
        interceptors = compileInterceptors();
        methodParameterResolver = compileParamResolvers();
        validators = compileValidators();
        if (logger.isDebugEnabled()) {
            logger
                    .debug("action info: " + controllerClass.getName() + "." + method.getName()
                            + ":");
            logger.debug("\t interceptors:" + Arrays.toString(interceptors));
            logger.debug("\t validators:" + Arrays.toString(validators));
        }
    }

    public NestedControllerInterceptorWrapper[] getRegisteredInterceptors() {
        return interceptors;
    }

    public Method getMethod() {
        return method;
    }

    public String[] getParameterNames() {
        return methodParameterResolver.getParameterNames();
    }

    private MethodParameterResolver compileParamResolvers() {
        ParameterNameDiscovererImpl parameterNameDiscoverer = new ParameterNameDiscovererImpl();
        ResolverFactoryImpl resolverFactory = new ResolverFactoryImpl();
        for (ParamResolver resolver : module.getCustomerResolvers()) {
            resolverFactory.addCustomerResolver(resolver);
        }
        return new MethodParameterResolver(this.controllerClass, method, parameterNameDiscoverer,
                resolverFactory);
    }

    @SuppressWarnings("unchecked")
    private NamedValidator[] compileValidators() {
        Class[] parameterTypes = method.getParameterTypes();
        List<NamedValidator> validators = module.getValidators();
        NamedValidator[] registeredValidators = new NamedValidator[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            if (methodParameterResolver.getParamAnnotationAt(i) == null
                    || methodParameterResolver.getParamAnnotationAt(i).validated()) {
                for (NamedValidator validator : validators) {
                    if (validator.supports(parameterTypes[i])) {
                        registeredValidators[i] = validator;
                        break;
                    }
                }
            }
        }
        //
        return registeredValidators;
    }

    private NestedControllerInterceptorWrapper[] compileInterceptors() {
        List<NestedControllerInterceptorWrapper> interceptors = module.getInterceptors();
        List<NestedControllerInterceptorWrapper> registeredInterceptors = new ArrayList<NestedControllerInterceptorWrapper>(
                interceptors.size());
        for (NestedControllerInterceptorWrapper interceptor : interceptors) {
            // 确定本拦截器的名字
            String name = interceptor.getName();
            String nameForUser = name;
            // 获取@Intercepted注解 (@Intercepted注解配置于控制器或其方法中，决定一个拦截器是否应该拦截之。没有配置按“需要”处理)
            Intercepted intercepted = method.getAnnotation(Intercepted.class);
            if (intercepted == null) {
                // 对于标注@Inherited的annotation，class.getAnnotation可以保证：如果本类没有，自动会从父类判断是否具有
                intercepted = this.controllerClass.getAnnotation(Intercepted.class);
            }
            // 通过@Intercepted注解的allow和deny排除拦截器
            if (intercepted != null) {
                // 3.1 先排除deny禁止的
                if (ArrayUtils.contains(intercepted.deny(), "*")
                        || ArrayUtils.contains(intercepted.deny(), nameForUser)) {
                    continue;
                }
                // 3.2 确认最大的allow允许
                else if (!ArrayUtils.contains(intercepted.allow(), "*")
                        && !ArrayUtils.contains(intercepted.allow(), nameForUser)) {
                    continue;
                }
            }
            // 取得拦截器同意后，注册到这个控制器方法中
            if (interceptor.isForAction(controllerClass, method)) {
                registeredInterceptors.add(interceptor);
            }
        }
        //
        return registeredInterceptors
                .toArray(new NestedControllerInterceptorWrapper[registeredInterceptors.size()]);
    }

    @Override
    public Object invoke(Rose rose, MatchResult<? extends Engine> mr, Object instruction,
            EngineChain chain) throws Throwable {
        try {
            return innerInvoke(rose, mr, instruction, chain);
        } catch (Throwable local) {
            throw createException(rose, local);
        }
    }

    public Object innerInvoke(Rose rose, MatchResult<? extends Engine> mr, Object instruction,
            EngineChain chain) throws Throwable {
        Invocation inv = rose.getInvocation();
        inv.getRequestPath().setActionPath(mr.getMatchedString());
        ((InvocationBean) inv).setMethod(method);
        ((InvocationBean) inv).setMethodParameterNames(this.methodParameterResolver
                .getParameterNames());

        // applies http features before the resolvers
        applyHttpFeatures(inv);

        //
        for (String matchResultParam : mr.getParameterNames()) {
            inv.addModel(matchResultParam, mr.getParameter(matchResultParam));
        }

        // creates parameter binding result (not bean, just simple type, like int, Integer, int[] ...
        ParameterBindingResult paramBindingResult = new ParameterBindingResult(inv);
        String paramBindingResultName = MODEL_KEY_PREFIX + paramBindingResult.getObjectName();
        inv.addModel(paramBindingResultName, paramBindingResult);

        // resolves method parameters, adds the method parameters to model
        Object[] methodParameters = methodParameterResolver.resolve(inv, paramBindingResult);
        ((InvocationBean) inv).setMethodParameters(methodParameters);
        String[] parameterNames = methodParameterResolver.getParameterNames();
        for (int i = 0; i < parameterNames.length; i++) {
            if (parameterNames[i] != null && methodParameters[i] != null
                    && inv.getModel().get(parameterNames[i]) != methodParameters[i]) {
                inv.addModel(parameterNames[i], methodParameters[i]);
            }
        }
        // validators
        for (int i = 0; i < this.validators.length; i++) {
            if (validators[i] != null && !(methodParameters[i] instanceof Errors)) {
                Errors errors = inv.getBindingResult(parameterNames[i]);
                if (errors == null) {
                    logger.error("not found Errors object from param " + parameterNames[i]
                            + " of method " + method.getDeclaringClass().getName() + "."
                            + method.getName());
                } else {
                    validators[i].validate(methodParameters[i], errors);
                }
            }
        }

        // invokes before-interceptors
        boolean broken = false;
        boolean[] bitSt = new boolean[interceptors.length];
        for (int i = 0; i < interceptors.length; i++) {
            final NestedControllerInterceptorWrapper interceptor = interceptors[i];
            if (!interceptor.isForDispatcher(inv.getRequestPath().getDispatcher())) {
                continue;
            }

            // returned by before method
            chain.addAfterCompletion(interceptor);
            instruction = interceptor.before(inv);
            bitSt[i] = true;
            if (logger.isDebugEnabled()) {
                logger.debug("interceptor[" + interceptor.getName() + "] do before and return '"
                        + instruction + "'");
            }

            if (instruction != null && !Boolean.TRUE.equals(instruction)) {
                if (instruction == inv) {
                    throw new IllegalArgumentException("Don't return an inv as an instruction: "
                            + inv.getRequestPath().getUri());
                }
                // the inv is broken

                // if false, don't render anything
                if (Boolean.FALSE.equals(instruction)) {
                    instruction = null;
                }
                broken = true;
                break; // just break, don't return
            }
        }

        // intercetporIndex负数代表被中断
        if (!broken) {
            // invoke
            instruction = method.invoke(controller, methodParameters);

            // @Return
            if (instruction == null) {
                Return returnAnnotation = method.getAnnotation(Return.class);
                if (returnAnnotation != null) {
                    instruction = returnAnnotation.value();
                }
            }
        }

        Object orginInstruction = instruction;

        // after the inv
        for (int i = bitSt.length - 1; i >= 0; i--) {
            if (!bitSt[i]) {
                continue;
            }
            instruction = interceptors[i].after(inv, instruction);
            if (logger.isDebugEnabled()) {
                logger.debug("invoke interceptor.after: [" + interceptors[i].getName()
                        + "] and return '" + instruction + "'");
            }
            // 拦截器返回null的，要恢复为原instruction
            // 这个功能非常有用!!
            if (instruction == null) {
                instruction = orginInstruction;
            }
        }
        return instruction;
    }

    private Exception createException(Rose rose, Throwable exception) {
        final RequestPath requestPath = rose.getInvocation().getRequestPath();
        StringBuilder sb = new StringBuilder(1024);
        sb.append("error happended: ").append(requestPath.getMethod());
        sb.append(" ").append(requestPath.getUri());
        sb.append("->");
        sb.append(this).append(" params=");
        sb.append(Arrays.toString(rose.getInvocation().getMethodParameters()));
        InvocationTargetException servletException = new InvocationTargetException(exception, sb
                .toString());
        return servletException;
    }

    private void applyHttpFeatures(final Invocation inv) throws UnsupportedEncodingException {
        HttpServletRequest request = inv.getRequest();
        HttpServletResponse response = inv.getResponse();
        HttpFeatures httpFeatures = method.getAnnotation(HttpFeatures.class);
        if (httpFeatures == null) {
            httpFeatures = this.controllerClass.getAnnotation(HttpFeatures.class);
        }
        if (httpFeatures != null) {
            if (StringUtils.isNotBlank(httpFeatures.contentType())) {
                response.setContentType(httpFeatures.contentType());
                if (logger.isDebugEnabled()) {
                    logger.debug("set response.contentType by metadata:"
                            + response.getContentType());
                }
            }
            if (StringUtils.isNotBlank(httpFeatures.charset())) {
                request.setCharacterEncoding(httpFeatures.charset());
                response.setCharacterEncoding(httpFeatures.charset());
                if (logger.isDebugEnabled()) {
                    logger.debug("set request/response.characterEncoding " + "by metadata:"
                            + httpFeatures.charset());
                }
            }
        }
        if (response.getContentType() == null) {
            response.setContentType("text/html;charset=UTF-8");
            if (logger.isDebugEnabled()) {
                logger.debug("set response.contentType by default:" + response.getContentType());
            }
        }
        if (request.getCharacterEncoding() == null) {
            request.setCharacterEncoding("UTF-8");
            if (logger.isDebugEnabled()) {
                logger.debug("set request.characterEncoding by default:"
                        + request.getCharacterEncoding());
            }
        }
        if (response.getCharacterEncoding() == null) {
            response.setCharacterEncoding("UTF-8");
            if (logger.isDebugEnabled()) {
                logger.debug("set response.characterEncoding by default:"
                        + response.getCharacterEncoding());
            }
        }
    }

    @Override
    public String toString() {
        if (toStringCache == null) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            String appPackageName = this.controllerClass.getPackage().getName();
            if (appPackageName.indexOf('.') != -1) {
                appPackageName = appPackageName.substring(0, appPackageName.lastIndexOf('.'));
            }
            String methodParamNames = "";
            for (int i = 0; i < parameterTypes.length; i++) {
                if (methodParamNames.length() == 0) {
                    methodParamNames = showSimpleName(parameterTypes[i], appPackageName);
                } else {
                    methodParamNames = methodParamNames + ", "
                            + showSimpleName(parameterTypes[i], appPackageName);
                }
            }
            toStringCache = ""//
                    //                    + this.controllerClass.getName() //
                    + showSimpleName(method.getReturnType(), appPackageName)
                    + " "
                    + method.getName() //
                    + "(" + methodParamNames + ")" //
            ;
        }
        return toStringCache;
    }

    private String showSimpleName(Class<?> parameterType, String appPackageName) {
        if (parameterType.getName().startsWith("net.paoding")
                || parameterType.getName().startsWith("java.lang")
                || parameterType.getName().startsWith("java.util")
                || parameterType.getName().startsWith(appPackageName)) {
            return parameterType.getSimpleName();
        }
        return parameterType.getName();
    }

    public void destroy() {

    }

}