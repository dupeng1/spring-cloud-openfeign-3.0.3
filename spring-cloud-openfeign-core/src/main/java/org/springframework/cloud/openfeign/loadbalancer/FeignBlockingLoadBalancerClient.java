/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.openfeign.loadbalancer;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import feign.Client;
import feign.Request;
import feign.Response;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.CompletionContext;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycle;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycleValidator;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.client.loadbalancer.ResponseData;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;

import static org.springframework.cloud.openfeign.loadbalancer.LoadBalancerUtils.buildRequestData;
import static org.springframework.cloud.openfeign.loadbalancer.LoadBalancerUtils.executeWithLoadBalancerLifecycleProcessing;

/**
 * A {@link Client} implementation that uses {@link LoadBalancerClient} to select a
 * {@link ServiceInstance} to use while resolving the request host.
 *
 * @author Olga Maciaszek-Sharma
 * @since 2.2.0
 */

/**
 * 集成Eureka后默认客户端类型，@FeignClient不指定url属性
 * 阻塞式请求，自带负载均衡。能够从注册中心根据负载策略获取具体的请求实例
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class FeignBlockingLoadBalancerClient implements Client {

	private static final Log LOG = LogFactory.getLog(FeignBlockingLoadBalancerClient.class);

	/**
	 * 具体类型是实现了Client接口的Default客户端
	 */
	private final Client delegate;

	/**
	 * 使用LoadBalanceClient选择一个服务实例来处理请求节点请求的客户端.
	 * LoadBalancerClient可以嗅探到各个服务实例的负载状况，然后根据负载策略选择一个可用服务实例，然后重定向到这个实例，然后处理一个(伪)RPC请求.
	 */
	private final LoadBalancerClient loadBalancerClient;

	private final LoadBalancerProperties properties;

	private final LoadBalancerClientFactory loadBalancerClientFactory;

	/**
	 * 发起网络调用
	 * @param delegate
	 * @param loadBalancerClient
	 * @param properties
	 * @param loadBalancerClientFactory
	 */
	public FeignBlockingLoadBalancerClient(Client delegate, LoadBalancerClient loadBalancerClient,
			LoadBalancerProperties properties, LoadBalancerClientFactory loadBalancerClientFactory) {
		this.delegate = delegate;
		this.loadBalancerClient = loadBalancerClient;
		this.properties = properties;
		this.loadBalancerClientFactory = loadBalancerClientFactory;
	}

	@Override
	public Response execute(Request request, Request.Options options) throws IOException {
		//原始uri
		final URI originalUri = URI.create(request.url());
		//服务id
		String serviceId = originalUri.getHost();
		Assert.state(serviceId != null, "Request URI does not contain a valid hostname: " + originalUri);
		String hint = getHint(serviceId);
		DefaultRequest<RequestDataContext> lbRequest = new DefaultRequest<>(
				new RequestDataContext(buildRequestData(request), hint));
		// LoadBalancerLifecycle是一些扩展生命周期的钩子类，可以在启动、执行以及结束时跟踪操作
		Set<LoadBalancerLifecycle> supportedLifecycleProcessors = LoadBalancerLifecycleValidator
				.getSupportedLifecycleProcessors(
						//获取LoadBalancerLifecycle
						loadBalancerClientFactory.getInstances(serviceId, LoadBalancerLifecycle.class),
						RequestDataContext.class, ResponseData.class, ServiceInstance.class);
		supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));
		// 通过loadBalancerClient选择一个服务实例
		//ReactorServiceInstanceLoadBalancer，这个接口有两个实现分别是
		//RandomLoadBalancer	随机
		//RoundRobinLoadBalancer	轮询
		ServiceInstance instance = loadBalancerClient.choose(serviceId, lbRequest);
		org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse = new DefaultResponse(
				instance);
		// 如果找不到一个可用实例，返回无可用实例的错误响应
		if (instance == null) {
			String message = "Load balancer does not contain an instance for the service " + serviceId;
			if (LOG.isWarnEnabled()) {
				LOG.warn(message);
			}
			supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
					.onComplete(new CompletionContext<ResponseData, ServiceInstance, RequestDataContext>(
							CompletionContext.Status.DISCARD, lbRequest, lbResponse)));
			return Response.builder().request(request).status(HttpStatus.SERVICE_UNAVAILABLE.value())
					.body(message, StandardCharsets.UTF_8).build();
		}
		// 重定向到可用服务实例地址
		String reconstructedUrl = loadBalancerClient.reconstructURI(instance, originalUri).toString();
		Request newRequest = buildRequest(request, reconstructedUrl);
		// 封装完新的请求后调用LoadBalanceUtils的静态方法，进行端到端调用
		return executeWithLoadBalancerLifecycleProcessing(delegate, options, newRequest, lbRequest, lbResponse,
				supportedLifecycleProcessors);
	}

	protected Request buildRequest(Request request, String reconstructedUrl) {
		return Request.create(request.httpMethod(), reconstructedUrl, request.headers(), request.body(),
				request.charset(), request.requestTemplate());
	}

	// Visible for Sleuth instrumentation
	public Client getDelegate() {
		return delegate;
	}

	private String getHint(String serviceId) {
		String defaultHint = properties.getHint().getOrDefault("default", "default");
		String hintPropertyValue = properties.getHint().get(serviceId);
		return hintPropertyValue != null ? hintPropertyValue : defaultHint;
	}

}
