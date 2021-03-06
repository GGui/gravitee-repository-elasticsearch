/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.repository.elasticsearch.configuration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch client configuration.
 *
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class ElasticConfiguration {

	private final static String DEFAULT_ELASTICSEARCH_ENDPOINT = "http://localhost:9200";

	@Autowired
	private Environment environment;
	
	/**
	 * Prefix index name. 
	 */
	@Value("${analytics.elasticsearch.index:gravitee}")
	private String indexName;
	
	/**
	 * Elasticsearch basic oauth login. 
	 */
	@Value("${analytics.elasticsearch.security.username:#{null}}")
	private String username;
	
	/**
	 * Elasticsearch basic oauth password. 
	 */
	@Value("${analytics.elasticsearch.security.password:#{null}}")
	private String password;

	/**
	 * Settings: number of shards
	 */
	@Value("${analytics.elasticsearch.settings.number_of_shards:5}")
	private int numberOfShards;

	/**
	 * Settings: number of replicas
	 */
	@Value("${analytics.elasticsearch.settings.number_of_replicas:1}")
	private int numberOfReplicas;

	/**
	 * Elasticsearch endpoints
	 */
	private List<Endpoint> endpoints;
	
	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public List<Endpoint> getEndpoints() {
		if(endpoints == null){
			endpoints = initializeEndpoints();
		}

		return endpoints;
	}

	public void setEndpoints(List<Endpoint> endpoints) {
		this.endpoints = endpoints;
	}

	public String getIndexName() {
		return indexName;
	}

	public void setIndexName(String indexName) {
		this.indexName = indexName;
	}

	private List<Endpoint> initializeEndpoints() {
		String key = String.format("analytics.elasticsearch.endpoints[%s]", 0);
		List<Endpoint> endpoints = new ArrayList<>();

		while (environment.containsProperty(key)) {
			String url = environment.getProperty(key);
			endpoints.add(new Endpoint(url));

			key = String.format("analytics.elasticsearch.endpoints[%s]", endpoints.size());
		}

		// Use default host if required
		if(endpoints.isEmpty()) {
			endpoints.add(new Endpoint(DEFAULT_ELASTICSEARCH_ENDPOINT));
		}

		return endpoints;
	}

	public int getNumberOfShards() {
		return numberOfShards;
	}

	public void setNumberOfShards(int numberOfShards) {
		this.numberOfShards = numberOfShards;
	}

	public int getNumberOfReplicas() {
		return numberOfReplicas;
	}

	public void setNumberOfReplicas(int numberOfReplicas) {
		this.numberOfReplicas = numberOfReplicas;
	}
}
