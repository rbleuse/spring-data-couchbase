/*
 * Copyright 2012-2022 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.couchbase.config;

import static com.couchbase.client.java.ClusterOptions.clusterOptions;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.convert.CustomConversions;
import org.springframework.data.couchbase.CouchbaseClientFactory;
import org.springframework.data.couchbase.SimpleCouchbaseClientFactory;
import org.springframework.data.couchbase.core.CouchbaseTemplate;
import org.springframework.data.couchbase.core.ReactiveCouchbaseTemplate;
import org.springframework.data.couchbase.core.convert.CouchbaseCustomConversions;
import org.springframework.data.couchbase.core.convert.MappingCouchbaseConverter;
import org.springframework.data.couchbase.core.convert.translation.JacksonTranslationService;
import org.springframework.data.couchbase.core.convert.translation.TranslationService;
import org.springframework.data.couchbase.core.mapping.CouchbaseMappingContext;
import org.springframework.data.couchbase.core.mapping.Document;
import org.springframework.data.couchbase.repository.config.ReactiveRepositoryOperationsMapping;
import org.springframework.data.couchbase.repository.config.RepositoryOperationsMapping;
import org.springframework.data.couchbase.transaction.CouchbaseCallbackTransactionManager;
import org.springframework.data.couchbase.transaction.CouchbaseTransactionInterceptor;
import org.springframework.data.couchbase.transaction.CouchbaseTransactionalOperator;
import org.springframework.data.mapping.model.CamelCaseAbbreviatingFieldNamingStrategy;
import org.springframework.data.mapping.model.FieldNamingStrategy;
import org.springframework.data.mapping.model.PropertyNameFieldNamingStrategy;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import com.couchbase.client.core.deps.com.fasterxml.jackson.databind.DeserializationFeature;
import com.couchbase.client.core.encryption.CryptoManager;
import com.couchbase.client.core.env.Authenticator;
import com.couchbase.client.core.env.PasswordAuthenticator;
import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.codec.JacksonJsonSerializer;
import com.couchbase.client.java.encryption.databind.jackson.EncryptionModule;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.java.json.JacksonTransformers;
import com.couchbase.client.java.json.JsonValueModule;
import com.couchbase.client.java.query.QueryScanConsistency;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Base class for Spring Data Couchbase configuration using JavaConfig.
 *
 * @author Michael Nitschinger
 * @author Simon Baslé
 * @author Stephane Nicoll
 * @author Subhashni Balakrishnan
 * @author Jorge Rodriguez Martin
 * @author Michael Reiche
 */
@Configuration
public abstract class AbstractCouchbaseConfiguration {

	/**
	 * The connection string which allows the SDK to connect to the cluster.
	 * <p>
	 * Note that the connection string can take many forms, in its simplest it is just a single hostname like "127.0.0.1".
	 * Please refer to the couchbase Java SDK documentation for all the different possibilities and options.
	 */
	public abstract String getConnectionString();

	/**
	 * The username of the user accessing Couchbase, configured on the cluster.
	 */
	public abstract String getUserName();

	/**
	 * The password used or the username to authenticate against the cluster.
	 */
	public abstract String getPassword();

	/**
	 * The name of the bucket that should be used (for example "travel-sample").
	 */
	public abstract String getBucketName();

	/**
	 * If a non-default scope should be used, override this method.
	 *
	 * @return the custom scope name or null if the default scope should be used (default).
	 */
	protected String getScopeName() {
		return null;
	}

	/**
	 * Allows to override the {@link Authenticator} used.
	 * <p>
	 * The default implementation uses the {@link PasswordAuthenticator} and takes the username and password from
	 * {@link #getUserName()} and {@link #getPassword()} respectively.
	 *
	 * @return the authenticator to be passed into the SDK.
	 */
	protected Authenticator authenticator() {
		return PasswordAuthenticator.create(getUserName(), getPassword());
	}

	/**
	 * The {@link CouchbaseClientFactory} provides access to the lower level SDK resources.
	 *
	 * @param couchbaseCluster the cluster reference from the SDK.
	 * @return the initialized factory.
	 */
	@Bean(name = BeanNames.COUCHBASE_CLIENT_FACTORY)
	public CouchbaseClientFactory couchbaseClientFactory(final Cluster couchbaseCluster) {
		return new SimpleCouchbaseClientFactory(couchbaseCluster, getBucketName(), getScopeName());
	}

	@Bean(destroyMethod = "disconnect")
	public Cluster couchbaseCluster(ClusterEnvironment couchbaseClusterEnvironment) {
		return Cluster.connect(getConnectionString(),
				clusterOptions(authenticator()).environment(couchbaseClusterEnvironment));
	}

	@Bean(destroyMethod = "shutdown")
	public ClusterEnvironment couchbaseClusterEnvironment() {
		ClusterEnvironment.Builder builder = ClusterEnvironment.builder();
		if (!nonShadowedJacksonPresent()) {
			throw new CouchbaseException("non-shadowed Jackson not present");
		}
		builder.jsonSerializer(JacksonJsonSerializer.create(couchbaseObjectMapper()));
		configureEnvironment(builder);
		return builder.build();
	}

	/**
	 * Can be overridden to customize the configuration of the environment before bootstrap.
	 *
	 * @param builder the builder that can be customized.
	 */
	protected void configureEnvironment(final ClusterEnvironment.Builder builder) {

	}

	@Bean(name = BeanNames.COUCHBASE_TEMPLATE)
	public CouchbaseTemplate couchbaseTemplate(CouchbaseClientFactory couchbaseClientFactory,
			MappingCouchbaseConverter mappingCouchbaseConverter, TranslationService couchbaseTranslationService) {
		return new CouchbaseTemplate(couchbaseClientFactory, mappingCouchbaseConverter, couchbaseTranslationService,
				getDefaultConsistency());
	}

	public CouchbaseTemplate couchbaseTemplate(CouchbaseClientFactory couchbaseClientFactory,
			MappingCouchbaseConverter mappingCouchbaseConverter) {
		return couchbaseTemplate(couchbaseClientFactory, mappingCouchbaseConverter, new JacksonTranslationService());
	}

	@Bean(name = BeanNames.REACTIVE_COUCHBASE_TEMPLATE)
	public ReactiveCouchbaseTemplate reactiveCouchbaseTemplate(CouchbaseClientFactory couchbaseClientFactory,
			MappingCouchbaseConverter mappingCouchbaseConverter, TranslationService couchbaseTranslationService) {
		return new ReactiveCouchbaseTemplate(couchbaseClientFactory, mappingCouchbaseConverter, couchbaseTranslationService,
				getDefaultConsistency());
	}

	public ReactiveCouchbaseTemplate reactiveCouchbaseTemplate(CouchbaseClientFactory couchbaseClientFactory,
			MappingCouchbaseConverter mappingCouchbaseConverter) {
		return reactiveCouchbaseTemplate(couchbaseClientFactory, mappingCouchbaseConverter,
				new JacksonTranslationService());
	}

	@Bean(name = BeanNames.COUCHBASE_OPERATIONS_MAPPING)
	public RepositoryOperationsMapping couchbaseRepositoryOperationsMapping(CouchbaseTemplate couchbaseTemplate) {
		// create a base mapping that associates all repositories to the default template
		RepositoryOperationsMapping baseMapping = new RepositoryOperationsMapping(couchbaseTemplate);
		// let the user tune it
		configureRepositoryOperationsMapping(baseMapping);
		return baseMapping;
	}

	/**
	 * In order to customize the mapping between repositories/entity types to couchbase templates, use the provided
	 * mapping's api (eg. in order to have different buckets backing different repositories).
	 *
	 * @param mapping the default mapping (will associate all repositories to the default template).
	 */
	protected void configureRepositoryOperationsMapping(RepositoryOperationsMapping mapping) {
		// NO_OP
	}

	@Bean(name = BeanNames.REACTIVE_COUCHBASE_OPERATIONS_MAPPING)
	public ReactiveRepositoryOperationsMapping reactiveCouchbaseRepositoryOperationsMapping(
			ReactiveCouchbaseTemplate reactiveCouchbaseTemplate) {
		// create a base mapping that associates all repositories to the default template
		ReactiveRepositoryOperationsMapping baseMapping = new ReactiveRepositoryOperationsMapping(
				reactiveCouchbaseTemplate);
		// let the user tune it
		configureReactiveRepositoryOperationsMapping(baseMapping);
		return baseMapping;
	}

	/**
	 * In order to customize the mapping between repositories/entity types to couchbase templates, use the provided
	 * mapping's api (eg. in order to have different buckets backing different repositories).
	 *
	 * @param mapping the default mapping (will associate all repositories to the default template).
	 */
	protected void configureReactiveRepositoryOperationsMapping(ReactiveRepositoryOperationsMapping mapping) {
		// NO_OP
	}

	/**
	 * Scans the mapping base package for classes annotated with {@link Document}.
	 *
	 * @throws ClassNotFoundException if initial entity sets could not be loaded.
	 */
	protected Set<Class<?>> getInitialEntitySet() throws ClassNotFoundException {
		String basePackage = getMappingBasePackage();
		Set<Class<?>> initialEntitySet = new HashSet<>();

		if (StringUtils.hasText(basePackage)) {
			ClassPathScanningCandidateComponentProvider componentProvider = new ClassPathScanningCandidateComponentProvider(
					false);
			componentProvider.addIncludeFilter(new AnnotationTypeFilter(Document.class));
			for (BeanDefinition candidate : componentProvider.findCandidateComponents(basePackage)) {
				initialEntitySet.add(
						ClassUtils.forName(candidate.getBeanClassName(), AbstractCouchbaseConfiguration.class.getClassLoader()));
			}
		}
		return initialEntitySet;
	}

	/**
	 * Determines the name of the field that will store the type information for complex types when using the
	 * {@link #mappingCouchbaseConverter(CouchbaseMappingContext, CouchbaseCustomConversions)}. Defaults to
	 * {@value MappingCouchbaseConverter#TYPEKEY_DEFAULT}.
	 *
	 * @see MappingCouchbaseConverter#TYPEKEY_DEFAULT
	 * @see MappingCouchbaseConverter#TYPEKEY_SYNCGATEWAY_COMPATIBLE
	 */
	public String typeKey() {
		return MappingCouchbaseConverter.TYPEKEY_DEFAULT;
	}

	/**
	 * Creates a {@link MappingCouchbaseConverter} using the configured {@link #couchbaseMappingContext}.
	 */
	@Bean
	public MappingCouchbaseConverter mappingCouchbaseConverter(CouchbaseMappingContext couchbaseMappingContext,
			CouchbaseCustomConversions couchbaseCustomConversions) {
		MappingCouchbaseConverter converter = new MappingCouchbaseConverter(couchbaseMappingContext, typeKey());
		converter.setCustomConversions(couchbaseCustomConversions);
		return converter;
	}

	/**
	 * Creates a {@link TranslationService}.
	 *
	 * @return TranslationService, defaulting to JacksonTranslationService.
	 */
	@Bean
	public TranslationService couchbaseTranslationService() {
		final JacksonTranslationService jacksonTranslationService = new JacksonTranslationService();
		jacksonTranslationService.afterPropertiesSet();

		// for sdk3, we need to ask the mapper _it_ uses to ignore extra fields...
		JacksonTransformers.MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		return jacksonTranslationService;
	}

	/**
	 * Creates a {@link CouchbaseMappingContext} equipped with entity classes scanned from the mapping base package.
	 */
	@Bean(BeanNames.COUCHBASE_MAPPING_CONTEXT)
	public CouchbaseMappingContext couchbaseMappingContext(CustomConversions customConversions) throws Exception {
		CouchbaseMappingContext mappingContext = new CouchbaseMappingContext();
		mappingContext.setInitialEntitySet(getInitialEntitySet());
		mappingContext.setSimpleTypeHolder(customConversions.getSimpleTypeHolder());
		mappingContext.setFieldNamingStrategy(fieldNamingStrategy());
		mappingContext.setAutoIndexCreation(autoIndexCreation());

		return mappingContext;
	}

	/**
	 * Creates a {@link ObjectMapper} for the jsonSerializer of the ClusterEnvironment
	 *
	 * @return ObjectMapper
	 */

	public ObjectMapper couchbaseObjectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		mapper.registerModule(new JsonValueModule());
		CryptoManager cryptoManager = null;
		if (cryptoManager != null) {
			mapper.registerModule(new EncryptionModule(cryptoManager));
		}
		return mapper;
	}

	/**
	 * The default blocking transaction manager. It is an implementation of CallbackPreferringTransactionManager
	 * CallbackPreferrringTransactionmanagers do not play well with test-cases that rely
	 * on @TestTransaction/@BeforeTransaction/@AfterTransaction
	 *
	 * @param clientFactory
	 * @return
	 */
	@Bean(BeanNames.COUCHBASE_TRANSACTION_MANAGER)
	CouchbaseCallbackTransactionManager couchbaseTransactionManager(CouchbaseClientFactory clientFactory) {
		return new CouchbaseCallbackTransactionManager(clientFactory);
	}

	/**
	 * The default transaction template manager.
	 *
	 * @param couchbaseTransactionManager
	 * @return
	 */
	@Bean(BeanNames.COUCHBASE_TRANSACTION_TEMPLATE)
	TransactionTemplate couchbaseTransactionTemplate(CouchbaseCallbackTransactionManager couchbaseTransactionManager) {
		return new TransactionTemplate(couchbaseTransactionManager);
	}
	/**
	 * The default TransactionalOperator.
	 *
	 * @param couchbaseCallbackTransactionManager
	 * @return
	 */
	@Bean(BeanNames.COUCHBASE_TRANSACTIONAL_OPERATOR)
	public CouchbaseTransactionalOperator couchbaseTransactionalOperator(
			CouchbaseCallbackTransactionManager couchbaseCallbackTransactionManager) {
		return CouchbaseTransactionalOperator.create(couchbaseCallbackTransactionManager);
	}

	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public TransactionInterceptor transactionInterceptor(TransactionManager couchbaseTransactionManager) {
		TransactionAttributeSource transactionAttributeSource = new AnnotationTransactionAttributeSource();
		TransactionInterceptor interceptor = new CouchbaseTransactionInterceptor(couchbaseTransactionManager,
				transactionAttributeSource);
		interceptor.setTransactionAttributeSource(transactionAttributeSource);
		if (couchbaseTransactionManager != null) {
			interceptor.setTransactionManager(couchbaseTransactionManager);
		}
		return interceptor;
	}

	/**
	 * Configure whether to automatically create indices for domain types by deriving the from the entity or not.
	 */
	protected boolean autoIndexCreation() {
		return false;
	}

	/**
	 * Register custom Converters in a {@link CustomConversions} object if required. These {@link CustomConversions} will
	 * be registered with the {@link #mappingCouchbaseConverter(CouchbaseMappingContext, CouchbaseCustomConversions)} )}
	 * and {@link #couchbaseMappingContext(CustomConversions)}. Returns an empty {@link CustomConversions} instance by
	 * default.
	 *
	 * @return must not be {@literal null}.
	 */
	@Bean(name = BeanNames.COUCHBASE_CUSTOM_CONVERSIONS)
	public CustomConversions customConversions() {
		return new CouchbaseCustomConversions(Collections.emptyList());
	}

	/**
	 * Return the base package to scan for mapped {@link Document}s. Will return the package name of the configuration
	 * class (the concrete class, not this one here) by default.
	 * <p>
	 * So if you have a {@code com.acme.AppConfig} extending {@link AbstractCouchbaseConfiguration} the base package will
	 * be considered {@code com.acme} unless the method is overridden to implement alternate behavior.
	 *
	 * @return the base package to scan for mapped {@link Document} classes or {@literal null} to not enable scanning for
	 *         entities.
	 */
	protected String getMappingBasePackage() {
		return getClass().getPackage().getName();
	}

	/**
	 * Set to true if field names should be abbreviated with the {@link CamelCaseAbbreviatingFieldNamingStrategy}.
	 *
	 * @return true if field names should be abbreviated, default is false.
	 */
	protected boolean abbreviateFieldNames() {
		return false;
	}

	/**
	 * Configures a {@link FieldNamingStrategy} on the {@link CouchbaseMappingContext} instance created.
	 *
	 * @return the naming strategy.
	 */
	protected FieldNamingStrategy fieldNamingStrategy() {
		return abbreviateFieldNames() ? new CamelCaseAbbreviatingFieldNamingStrategy()
				: PropertyNameFieldNamingStrategy.INSTANCE;
	}

	private boolean nonShadowedJacksonPresent() {
		try {
			JacksonJsonSerializer.preflightCheck();
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	public QueryScanConsistency getDefaultConsistency() {
		return null;
	}
}
