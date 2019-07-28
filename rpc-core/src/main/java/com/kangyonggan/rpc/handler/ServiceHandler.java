package com.kangyonggan.rpc.handler;

import com.kangyonggan.rpc.constants.FaultPolicy;
import com.kangyonggan.rpc.constants.RpcPojo;
import com.kangyonggan.rpc.core.*;
import com.kangyonggan.rpc.pojo.Refrence;
import com.kangyonggan.rpc.pojo.Service;
import com.kangyonggan.rpc.util.*;
import org.apache.log4j.Logger;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * 动态代理。重新invoke，从远程获取服务
 *
 * @author kangyonggan
 * @since 2019-02-15
 */
public class ServiceHandler implements InvocationHandler {

    private Logger logger = Logger.getLogger(ServiceHandler.class);

    private Refrence refrence;

    public ServiceHandler(Refrence refrence) {
        this.refrence = refrence;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        return invoke(method.getName(), method.getParameterTypes(), args, method.getReturnType());
    }

    public Object invoke(String methodName, Class[] argTypes, Object[] args, Class<?> returnType) throws Throwable {
        // 准备请求参数
        RpcRequest request = new RpcRequest();

        // 通用参数
        request.setUuid(RpcContext.getUuid().get());
        request.setClientApplicationName(RpcContext.getApplicationName());
        request.setClientIp(RpcContext.getLocalIp());

        // 必要参数
        request.setClassName(refrence.getName());
        request.setMethodName(methodName);
        request.setTypes(getTypes(argTypes));
        request.setArgs(args);

        // 判断是否异步调用
        if (refrence.isAsync()) {
            Object result = null;
            FutureTask<Object> futureTask = AsynUtils.submitTask(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    try {
                        return remoteCall(refrence, request, returnType);
                    } catch (Throwable e) {
                        logger.error("异步调用发生异常", e);
                        throw new RuntimeException(e);
                    }
                }
            });

            // 异步放入上下文中，提交调用方法后，可以从上下文中获取结果
            RpcContext.getFutureTask().set(futureTask);

            // 判断基础类型，返回默认值，否则自动转换会报空指针
            if (TypeParseUtil.isBasicType(returnType)) {
                result = TypeParseUtil.getBasicTypeDefaultValue(returnType);
            }

            logger.info("异步调用直接返回:" + result);
            return result;
        }

        // 同步调用
        return remoteCall(refrence, request, returnType);
    }

    /**
     * 远程调用
     *
     * @param refrence
     * @param request
     * @param returnType
     * @return
     * @throws Throwable
     */
    private Object remoteCall(Refrence refrence, RpcRequest request, Class<?> returnType) throws Throwable {
        // 判断是否服务降级
        if (ServiceDegradeUtil.exists(refrence.getName())) {
            logger.info("[降级服务]，直接返回null");

            // 判断基础类型，返回默认值，否则自动转换会报空指针
            if (TypeParseUtil.isBasicType(returnType)) {
                return TypeParseUtil.getBasicTypeDefaultValue(returnType);
            }
            return null;
        }

        RpcResponse response = null;
        Date beginTime = new Date();

        try {
            RpcClient rpcClient = new RpcClient(refrence);//客户端调用对象，创建到Server的连接
            Service service = rpcClient.connectRemoteService();//创建到Server的连接
            request.setService(service);
            response = rpcClient.remoteCall(request);
            return response.getResult();
        } catch (Throwable e) {
            logger.error(e);

            if (!StringUtils.isEmpty(refrence.getInterceptor())) {
                RpcInterceptor interceptor = InterceptorUtil.get(refrence.getInterceptor());
                // 拦截器
                interceptor.exceptionHandle(refrence, request, e);
            }

            if (refrence.getFault().equals(FaultPolicy.FAIL_FAST.getName())) {
                // 快速失败
                logger.info("远程调用失败，使用快速失败策略");
                throw e;
            } else if (refrence.getFault().equals(FaultPolicy.FAIL_RETRY.getName())) {
                // 失败重试
                logger.info("远程调用失败，使用失败重试策略");
                return new RpcClient(refrence).remoteCall(request);
            } else if (refrence.getFault().equals(FaultPolicy.FAIL_SAFE.getName())) {
                // 失败安全
                logger.info("远程调用失败，使用失败安全策略");

                // 判断基础类型，返回默认值，否则自动转换会报空指针
                if (TypeParseUtil.isBasicType(returnType)) {
                    return TypeParseUtil.getBasicTypeDefaultValue(returnType);
                }
                return null;
            }

            logger.error("远程调用失败，暂不支持此容错策略");
            throw e;
        } finally {
            Date endTime = new Date();
            // 监控
            if (SpringUtils.getApplicationContext().containsBean(RpcPojo.monitor.name())) {
                // 收集数据
                MonitorObject monitorObject = new MonitorObject();
                monitorObject.setRefrence(refrence);
                monitorObject.setRpcRequest(request);
                monitorObject.setRpcResponse(response);
                monitorObject.setBeginTime(beginTime);
                monitorObject.setEndTime(endTime);

                // 异步发送到监控服务端
                AsynUtils.submitTask(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        MonitorClient.getInstance().send(monitorObject);
                        return null;
                    }
                });
            }
        }
    }

    /**
     * 获取方法的参数类型
     *
     * @param method
     * @return
     */
    private String[] getTypes(Method method) {
        String[] types = new String[method.getParameterTypes().length];
        for (int i = 0; i < method.getParameterTypes().length; i++) {
            types[i] = method.getParameterTypes()[i].getName();
        }
        return types;
    }

    /**
     * 获取方法的参数类型
     *
     * @param methodTypes
     * @return
     */
    private String[] getTypes(Class<?>[] methodTypes) {
        String[] types = new String[methodTypes.length];
        for (int i = 0; i < methodTypes.length; i++) {
            types[i] = methodTypes[i].getName();
        }
        return types;
    }
}
