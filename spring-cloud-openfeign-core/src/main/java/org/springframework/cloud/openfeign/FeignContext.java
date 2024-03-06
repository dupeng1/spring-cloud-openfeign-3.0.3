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

package org.springframework.cloud.openfeign;

import java.util.Map;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.cloud.context.named.NamedContextFactory;
import org.springframework.lang.Nullable;

/**
 * A factory that creates instances of feign classes. It creates a Spring
 * ApplicationContext per client name, and extracts the beans that it needs from there.
 *
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Matt King
 * @author Jasbir Singh
 */
/**
 * 对外提供的实例工厂类，为每个Feign客户端创建一个ApplicationContext，并从中提取所需的Bean
 * 外部主要通过该工厂类获取FeignClient客户端实例，通过继承NamedContextFactory会为每一个FeignClient客户端都创建一个ApplicationContext，
 * 以便于从每个FeignClient的ApplicationContext获取指定的Bean对象，这个工厂类最主要的特征就是隔离了每个FeignClient，
 * 每个FeignClient客户端都有自己的一个ApplicationContext上下文。
 */
public class FeignContext extends NamedContextFactory<FeignClientSpecification> {

	public FeignContext() {
		//设置FeignClientsConfiguration配置类，后面创建容器加载配置类：
		super(FeignClientsConfiguration.class, "feign", "feign.client.name");
	}

	@Nullable
	public <T> T getInstanceWithoutAncestors(String name, Class<T> type) {
		try {
			return BeanFactoryUtils.beanOfType(getContext(name), type);
		}
		catch (BeansException ex) {
			return null;
		}
	}
	//获取实例
	@Nullable
	public <T> Map<String, T> getInstancesWithoutAncestors(String name, Class<T> type) {
		return getContext(name).getBeansOfType(type);
	}
	//获取实例集合
	public <T> T getInstance(String contextName, String beanName, Class<T> type) {
		return getContext(contextName).getBean(beanName, type);
	}

}
