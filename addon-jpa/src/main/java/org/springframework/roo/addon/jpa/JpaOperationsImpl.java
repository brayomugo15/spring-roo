package org.springframework.roo.addon.jpa;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Logger;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.springframework.roo.addon.propfiles.PropFileOperations;
import org.springframework.roo.metadata.MetadataService;
import org.springframework.roo.process.manager.FileManager;
import org.springframework.roo.process.manager.MutableFile;
import org.springframework.roo.project.Dependency;
import org.springframework.roo.project.DependencyScope;
import org.springframework.roo.project.Filter;
import org.springframework.roo.project.Path;
import org.springframework.roo.project.Plugin;
import org.springframework.roo.project.ProjectMetadata;
import org.springframework.roo.project.ProjectOperations;
import org.springframework.roo.project.Property;
import org.springframework.roo.project.Repository;
import org.springframework.roo.project.Resource;
import org.springframework.roo.support.logging.HandlerUtils;
import org.springframework.roo.support.util.Assert;
import org.springframework.roo.support.util.DomUtils;
import org.springframework.roo.support.util.FileCopyUtils;
import org.springframework.roo.support.util.IOUtils;
import org.springframework.roo.support.util.StringUtils;
import org.springframework.roo.support.util.TemplateUtils;
import org.springframework.roo.support.util.XmlElementBuilder;
import org.springframework.roo.support.util.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Provides JPA configuration operations.
 * 
 * @author Stefan Schmidt
 * @author Alan Stewart
 * @since 1.0
 */
@Component
@Service
public class JpaOperationsImpl implements JpaOperations {
	
	// Constants
	private static final Dependency JSTL_IMPL_DEPENDENCY = new Dependency("org.glassfish.web", "jstl-impl", "1.2");
	private static final Logger LOGGER = HandlerUtils.getLogger(JpaOperationsImpl.class);
	private static final String DATABASE_URL = "database.url";
	private static final String DATABASE_DRIVER = "database.driverClassName";
	private static final String DATABASE_USERNAME = "database.username";
	private static final String DATABASE_PASSWORD = "database.password";
	private static final String DEFAULT_PERSISTENCE_UNIT = "persistenceUnit";
	private static final String GAE_PERSISTENCE_UNIT_NAME = "transactions-optional";
	private static final String PERSISTENCE_UNIT = "persistence-unit";
	
	static final String APPLICATION_CONTEXT_XML = "applicationContext.xml";
	static final String POM_XML = "pom.xml";
	
	// Fields (package access so unit tests can inject mocks)
	@Reference FileManager fileManager;
	@Reference MetadataService metadataService;
	@Reference ProjectOperations projectOperations;
	@Reference PropFileOperations propFileOperations;
	
	public boolean isJpaInstallationPossible() {
		return projectOperations.isProjectAvailable() && !fileManager.exists(getPersistencePath());
	}

	public boolean isJpaInstalled() {
		return projectOperations.isProjectAvailable() && fileManager.exists(getPersistencePath());
	}

	public boolean hasDatabaseProperties() {
		return fileManager.exists(getDatabasePropertiesPath());
	}

	public SortedSet<String> getDatabaseProperties() {
		if (fileManager.exists(getDatabasePropertiesPath())) {
			return propFileOperations.getPropertyKeys(Path.SPRING_CONFIG_ROOT, "database.properties", true);
		}
		return getPropertiesFromDataNucleusConfiguration();
	}

	private String getPersistencePath() {
		return projectOperations.getPathResolver().getIdentifier(Path.SRC_MAIN_RESOURCES, "META-INF/persistence.xml");
	}

	private String getDatabasePropertiesPath() {
		return projectOperations.getPathResolver().getIdentifier(Path.SPRING_CONFIG_ROOT, "database.properties");
	}

	public void configureJpa(final OrmProvider ormProvider, final JdbcDatabase jdbcDatabase, final String jndi, final String applicationId, final String hostName, final String databaseName, final String userName, final String password, final String transactionManager, final String persistenceUnit) {
		Assert.notNull(ormProvider, "ORM provider required");
		Assert.notNull(jdbcDatabase, "JDBC database required");

		// Parse the configuration.xml file
		final Element configuration = XmlUtils.getConfiguration(getClass());
		
		// Get the first part of the XPath expressions for unwanted databases and ORM providers
		final String databaseXPath = getDbXPath(getUnwantedDatabases(jdbcDatabase));
		final String providersXPath = getProviderXPath(getUnwantedOrmProviders(ormProvider));

		if (jdbcDatabase != JdbcDatabase.GOOGLE_APP_ENGINE) {
			updateEclipsePlugin(false);
			updateDataNucleusPlugin(false);
			projectOperations.updateDependencyScope(JSTL_IMPL_DEPENDENCY, null);
		}

		updateApplicationContext(ormProvider, jdbcDatabase, jndi, transactionManager, persistenceUnit);
		updatePersistenceXml(ormProvider, jdbcDatabase, hostName, databaseName, userName, password, persistenceUnit);
		manageGaeXml(ormProvider, jdbcDatabase, applicationId);
		updateDbdcConfigProperties(ormProvider, jdbcDatabase, hostName, userName, password, StringUtils.defaultIfEmpty(persistenceUnit, DEFAULT_PERSISTENCE_UNIT));

		if (!StringUtils.hasText(jndi)) {
			updateDatabaseProperties(ormProvider, jdbcDatabase, hostName, databaseName, userName, password);
		}

		updateLog4j(ormProvider);
		updatePomProperties(configuration, ormProvider, jdbcDatabase);
		updateDependencies(configuration, ormProvider, jdbcDatabase, databaseXPath, providersXPath);
		updateRepositories(configuration, ormProvider, jdbcDatabase);
		updatePluginRepositories(configuration, ormProvider, jdbcDatabase);
		updateFilters(configuration, ormProvider, jdbcDatabase, databaseXPath, providersXPath);
		updateResources(configuration, ormProvider, jdbcDatabase, databaseXPath, providersXPath);
		updateBuildPlugins(configuration, ormProvider, jdbcDatabase, databaseXPath, providersXPath);
	}

	private void updateApplicationContext(final OrmProvider ormProvider, final JdbcDatabase jdbcDatabase, final String jndi, String transactionManager, final String persistenceUnit) {
		final String contextPath = projectOperations.getPathResolver().getIdentifier(Path.SPRING_CONFIG_ROOT, APPLICATION_CONTEXT_XML);
		final Document appCtx = XmlUtils.readXml(fileManager.getInputStream(contextPath));
		final Element root = appCtx.getDocumentElement();

		// Checking for existence of configurations, if found abort
		Element dataSource = XmlUtils.findFirstElement("/beans/bean[@id = 'dataSource']", root);
		Element dataSourceJndi = XmlUtils.findFirstElement("/beans/jndi-lookup[@id = 'dataSource']", root);

		if (ormProvider == OrmProvider.DATANUCLEUS || ormProvider == OrmProvider.DATANUCLEUS_2) {
			if (dataSource != null) {
				root.removeChild(dataSource);
			}
			if (dataSourceJndi != null) {
				root.removeChild(dataSourceJndi);
			}
		} else if (!StringUtils.hasText(jndi) && dataSource == null) {
			dataSource = appCtx.createElement("bean");
			dataSource.setAttribute("class", "org.apache.commons.dbcp.BasicDataSource");
			dataSource.setAttribute("destroy-method", "close");
			dataSource.setAttribute("id", "dataSource");
			dataSource.appendChild(createPropertyElement("driverClassName", "${database.driverClassName}", appCtx));
			dataSource.appendChild(createPropertyElement("url", "${database.url}", appCtx));
			dataSource.appendChild(createPropertyElement("username", "${database.username}", appCtx));
			dataSource.appendChild(createPropertyElement("password", "${database.password}", appCtx));
			dataSource.appendChild(createPropertyElement("testOnBorrow", "true", appCtx));
			dataSource.appendChild(createPropertyElement("testOnReturn", "true", appCtx));
			dataSource.appendChild(createPropertyElement("testWhileIdle", "true", appCtx));
			dataSource.appendChild(createPropertyElement("timeBetweenEvictionRunsMillis", "1800000", appCtx));
			dataSource.appendChild(createPropertyElement("numTestsPerEvictionRun", "3", appCtx));
			dataSource.appendChild(createPropertyElement("minEvictableIdleTimeMillis", "1800000", appCtx));
			root.appendChild(dataSource);
			if (dataSourceJndi != null) {
				dataSourceJndi.getParentNode().removeChild(dataSourceJndi);
			}
		} else if (StringUtils.hasText(jndi)) {
			if (dataSourceJndi == null) {
				dataSourceJndi = appCtx.createElement("jee:jndi-lookup");
				dataSourceJndi.setAttribute("id", "dataSource");
				root.appendChild(dataSourceJndi);
			}
			dataSourceJndi.setAttribute("jndi-name", jndi);
			if (dataSource != null) {
				dataSource.getParentNode().removeChild(dataSource);
			}
		}

		if (dataSource != null) {
			final Element validationQueryElement = XmlUtils.findFirstElement("property[@name = 'validationQuery']", dataSource);
			if (validationQueryElement != null) {
				dataSource.removeChild(validationQueryElement);
			}
			String validationQuery = "";
			switch (jdbcDatabase) {
				case ORACLE:
					validationQuery = "SELECT 1 FROM DUAL";
					break;
				case POSTGRES:
					validationQuery = "SELECT version();";
					break;
				case MYSQL:
					validationQuery = "SELECT 1";
					break;
			}
			if (StringUtils.hasText(validationQuery)) {
				dataSource.appendChild(createPropertyElement("validationQuery", validationQuery, appCtx));
			}
		}
		
		transactionManager = StringUtils.hasText(transactionManager) ? transactionManager : "transactionManager";
		Element transactionManagerElement = XmlUtils.findFirstElement("/beans/bean[@id = '" + transactionManager + "']", root);
		if (transactionManagerElement == null) {
			transactionManagerElement = appCtx.createElement("bean");
			transactionManagerElement.setAttribute("id", transactionManager);
			transactionManagerElement.setAttribute("class", "org.springframework.orm.jpa.JpaTransactionManager");
			transactionManagerElement.appendChild(createRefElement("entityManagerFactory", "entityManagerFactory", appCtx));
			root.appendChild(transactionManagerElement);
		}

		Element aspectJTxManager = XmlUtils.findFirstElement("/beans/annotation-driven", root);
		if (aspectJTxManager == null) {
			aspectJTxManager = appCtx.createElement("tx:annotation-driven");
			aspectJTxManager.setAttribute("mode", "aspectj");
			aspectJTxManager.setAttribute("transaction-manager", transactionManager);
			root.appendChild(aspectJTxManager);
		} else {
			aspectJTxManager.setAttribute("transaction-manager", transactionManager);
		}

		Element entityManagerFactory = XmlUtils.findFirstElement("/beans/bean[@id = 'entityManagerFactory']", root);
		if (entityManagerFactory != null) {
			root.removeChild(entityManagerFactory);
		}

		entityManagerFactory = appCtx.createElement("bean");
		entityManagerFactory.setAttribute("id", "entityManagerFactory");

		switch (jdbcDatabase) {
			case GOOGLE_APP_ENGINE:
				entityManagerFactory.setAttribute("class", "org.springframework.orm.jpa.LocalEntityManagerFactoryBean");
				entityManagerFactory.appendChild(createPropertyElement("persistenceUnitName", StringUtils.defaultIfEmpty(persistenceUnit, GAE_PERSISTENCE_UNIT_NAME), appCtx));
				break;
			default:
				entityManagerFactory.setAttribute("class", "org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean");
				entityManagerFactory.appendChild(createPropertyElement("persistenceUnitName", StringUtils.defaultIfEmpty(persistenceUnit, DEFAULT_PERSISTENCE_UNIT), appCtx));
				if (!ormProvider.name().startsWith("DATANUCLEUS")) {
					entityManagerFactory.appendChild(createRefElement("dataSource", "dataSource", appCtx));
				}
				break;
		}

		root.appendChild(entityManagerFactory);

		DomUtils.removeTextNodes(root);

		fileManager.createOrUpdateTextFileIfRequired(contextPath, XmlUtils.nodeToString(appCtx), false);
	}

	private void updatePersistenceXml(final OrmProvider ormProvider, final JdbcDatabase jdbcDatabase, final String hostName, final String databaseName, String userName, final String password, final String persistenceUnit) {
		final String persistencePath = getPersistencePath();
		final InputStream inputStream;
		if (fileManager.exists(persistencePath)) {
			inputStream = fileManager.getInputStream(persistencePath);
		} else {
			inputStream = TemplateUtils.getTemplate(getClass(), "persistence-template.xml");
			Assert.notNull(inputStream, "Could not acquire persistence.xml template");
		}
		final Document persistence = XmlUtils.readXml(inputStream);
		final Element root = persistence.getDocumentElement();
		final Element persistenceElement = XmlUtils.findFirstElement("/persistence", root);
		Assert.notNull(persistenceElement, "No persistence element found");

		Element persistenceUnitElement;
		if (StringUtils.hasText(persistenceUnit)) {
			persistenceUnitElement = XmlUtils.findFirstElement(PERSISTENCE_UNIT + "[@name = '" + persistenceUnit + "']", persistenceElement);
		} else {
			persistenceUnitElement = XmlUtils.findFirstElement(PERSISTENCE_UNIT + "[@name = '" + (jdbcDatabase == JdbcDatabase.GOOGLE_APP_ENGINE ? GAE_PERSISTENCE_UNIT_NAME : DEFAULT_PERSISTENCE_UNIT) + "']", persistenceElement);
		}

		if (persistenceUnitElement != null) {
			while (persistenceUnitElement.getFirstChild() != null) {
				persistenceUnitElement.removeChild(persistenceUnitElement.getFirstChild());
			}
		} else {
			persistenceUnitElement = persistence.createElement(PERSISTENCE_UNIT);
			persistenceElement.appendChild(persistenceUnitElement);
		}

		// Set attributes for DataNuclueus 1.1.x/GAE-specific requirements
		switch (ormProvider) {
			case DATANUCLEUS:
				persistenceElement.setAttribute("version", "1.0");
				persistenceElement.setAttribute("xsi:schemaLocation", "http://java.sun.com/xml/ns/persistence http://java.sun.com/xml/ns/persistence/persistence_1_0.xsd");
				break;
			default:
				persistenceElement.setAttribute("version", "2.0");
				persistenceElement.setAttribute("xsi:schemaLocation", "http://java.sun.com/xml/ns/persistence http://java.sun.com/xml/ns/persistence/persistence_2_0.xsd");
				break;
		}

		// Add provider element
		final Element provider = persistence.createElement("provider");
		switch (jdbcDatabase) {
			case GOOGLE_APP_ENGINE:
				persistenceUnitElement.setAttribute("name", (StringUtils.defaultIfEmpty(persistenceUnit, GAE_PERSISTENCE_UNIT_NAME)));
				persistenceUnitElement.removeAttribute("transaction-type");
				provider.setTextContent(ormProvider.getAlternateAdapter());
				break;
			case DATABASE_DOT_COM:
				persistenceUnitElement.setAttribute("name", (StringUtils.defaultIfEmpty(persistenceUnit, DEFAULT_PERSISTENCE_UNIT)));
				persistenceUnitElement.removeAttribute("transaction-type");
				provider.setTextContent(ormProvider.getAlternateAdapter());
				break;
			default:
				persistenceUnitElement.setAttribute("name", (StringUtils.defaultIfEmpty(persistenceUnit, DEFAULT_PERSISTENCE_UNIT)));
				persistenceUnitElement.setAttribute("transaction-type", "RESOURCE_LOCAL");
				provider.setTextContent(ormProvider.getAdapter());
				break;
		}
		persistenceUnitElement.appendChild(provider);

		// Add properties
		final InputStream dialectsInputStream = TemplateUtils.getTemplate(getClass(), "jpa-dialects.properties");
		Assert.notNull(dialectsInputStream, "Could not acquire jpa-dialects.properties");
		final Properties dialects = propFileOperations.loadProperties(dialectsInputStream);
		final Element properties = persistence.createElement("properties");
		boolean isDbreProject = fileManager.exists(projectOperations.getPathResolver().getIdentifier(Path.SRC_MAIN_RESOURCES, "dbre.xml"));
		switch (ormProvider) {
			case HIBERNATE:
				properties.appendChild(createPropertyElement("hibernate.dialect", dialects.getProperty(ormProvider.name() + "." + jdbcDatabase.name()), persistence));
				properties.appendChild(persistence.createComment(" value=\"create\" to build a new database on each run; value=\"update\" to modify an existing database; value=\"create-drop\" means the same as \"create\" but also drops tables when Hibernate closes; value=\"validate\" makes no changes to the database ")); // ROO-627
				String hbm2dll = "create";
				if (isDbreProject || jdbcDatabase == JdbcDatabase.DB2_400) {
					hbm2dll = "validate";
				}
				properties.appendChild(createPropertyElement("hibernate.hbm2ddl.auto", hbm2dll, persistence));
				properties.appendChild(createPropertyElement("hibernate.ejb.naming_strategy", "org.hibernate.cfg.ImprovedNamingStrategy", persistence));
				properties.appendChild(createPropertyElement("hibernate.connection.charSet", "UTF-8", persistence));
				properties.appendChild(persistence.createComment(" Uncomment the following two properties for JBoss only "));
				properties.appendChild(persistence.createComment(" property name=\"hibernate.validator.apply_to_ddl\" value=\"false\" /"));
				properties.appendChild(persistence.createComment(" property name=\"hibernate.validator.autoregister_listeners\" value=\"false\" /"));
				break;
			case OPENJPA:
				properties.appendChild(createPropertyElement("openjpa.jdbc.DBDictionary", dialects.getProperty(ormProvider.name() + "." + jdbcDatabase.name()), persistence));
				properties.appendChild(persistence.createComment(" value=\"buildSchema\" to runtime forward map the DDL SQL; value=\"validate\" makes no changes to the database ")); // ROO-627
				properties.appendChild(createPropertyElement("openjpa.jdbc.SynchronizeMappings", (isDbreProject ? "validate" : "buildSchema"), persistence));
				properties.appendChild(createPropertyElement("openjpa.RuntimeUnenhancedClasses", "supported", persistence));
				break;
			case ECLIPSELINK:
				properties.appendChild(createPropertyElement("eclipselink.target-database", dialects.getProperty(ormProvider.name() + "." + jdbcDatabase.name()), persistence));
				properties.appendChild(persistence.createComment(" value=\"drop-and-create-tables\" to build a new database on each run; value=\"create-tables\" creates new tables if needed; value=\"none\" makes no changes to the database ")); // ROO-627
				properties.appendChild(createPropertyElement("eclipselink.ddl-generation", (isDbreProject ? "none" : "drop-and-create-tables"), persistence));
				properties.appendChild(createPropertyElement("eclipselink.ddl-generation.output-mode", "database", persistence));
				properties.appendChild(createPropertyElement("eclipselink.weaving", "static", persistence));
				break;
			case DATANUCLEUS:
			case DATANUCLEUS_2:
				String connectionString = getConnectionString(jdbcDatabase, hostName, databaseName);
				switch (jdbcDatabase) {
					case GOOGLE_APP_ENGINE:
						properties.appendChild(createPropertyElement("datanucleus.NontransactionalRead", "true", persistence));
						properties.appendChild(createPropertyElement("datanucleus.NontransactionalWrite", "true", persistence));
						properties.appendChild(createPropertyElement("datanucleus.autoCreateSchema", "false", persistence));
						break;
					case DATABASE_DOT_COM:
						properties.appendChild(createPropertyElement("datanucleus.storeManagerType", "force", persistence));
						properties.appendChild(createPropertyElement("datanucleus.Optimistic", "false", persistence));
						properties.appendChild(createPropertyElement("datanucleus.datastoreTransactionDelayOperations", "true", persistence));
						properties.appendChild(createPropertyElement("datanucleus.autoCreateSchema", new Boolean(!isDbreProject).toString(), persistence));
						break;
					default:
						properties.appendChild(createPropertyElement("datanucleus.ConnectionDriverName", jdbcDatabase.getDriverClassName(), persistence));
						properties.appendChild(createPropertyElement("datanucleus.autoCreateSchema", new Boolean(!isDbreProject).toString(), persistence));
						final ProjectMetadata projectMetadata = (ProjectMetadata) metadataService.get(ProjectMetadata.getProjectIdentifier());
						connectionString = connectionString.replace("TO_BE_CHANGED_BY_ADDON", projectMetadata.getProjectName());
						if (jdbcDatabase.getKey().equals("HYPERSONIC") || jdbcDatabase == JdbcDatabase.H2_IN_MEMORY || jdbcDatabase == JdbcDatabase.SYBASE) {
							userName = StringUtils.hasText(userName) ? userName : "sa";
						}
						properties.appendChild(createPropertyElement("datanucleus.storeManagerType", "rdbms", persistence));
				}

			if (jdbcDatabase != JdbcDatabase.DATABASE_DOT_COM) { 
				// These are specified in the connection properties file
				properties.appendChild(createPropertyElement("datanucleus.ConnectionURL", connectionString, persistence));
				properties.appendChild(createPropertyElement("datanucleus.ConnectionUserName", userName, persistence));
				properties.appendChild(createPropertyElement("datanucleus.ConnectionPassword", password, persistence));
			}
			
			properties.appendChild(createPropertyElement("datanucleus.autoCreateTables", Boolean.toString(!isDbreProject), persistence));
			properties.appendChild(createPropertyElement("datanucleus.autoCreateColumns", "false", persistence));
			properties.appendChild(createPropertyElement("datanucleus.autoCreateConstraints", "false", persistence));
			properties.appendChild(createPropertyElement("datanucleus.validateTables", "false", persistence));
			properties.appendChild(createPropertyElement("datanucleus.validateConstraints", "false", persistence));
			properties.appendChild(createPropertyElement("datanucleus.jpa.addClassTransformer", "false", persistence));
			break;
		}

		persistenceUnitElement.appendChild(properties);

		fileManager.createOrUpdateTextFileIfRequired(persistencePath, XmlUtils.nodeToString(persistence), false);
		
		if (jdbcDatabase != JdbcDatabase.GOOGLE_APP_ENGINE && (ormProvider == OrmProvider.DATANUCLEUS || ormProvider == OrmProvider.DATANUCLEUS_2)) {
			LOGGER.warning("Please update your database details in src/main/resources/META-INF/persistence.xml.");
		}
	}

	private String getConnectionString(final JdbcDatabase jdbcDatabase, String hostName, final String databaseName) {
		String connectionString = jdbcDatabase.getConnectionString();
		if (connectionString.contains("TO_BE_CHANGED_BY_ADDON")) {
			final ProjectMetadata projectMetadata = (ProjectMetadata) metadataService.get(ProjectMetadata.getProjectIdentifier());
			connectionString = connectionString.replace("TO_BE_CHANGED_BY_ADDON", (StringUtils.hasText(databaseName) ? databaseName : projectMetadata.getProjectName()));
		} else {
			if (StringUtils.hasText(databaseName)) {
				// Oracle uses a different connection URL - see ROO-1203
				final String dbDelimiter = jdbcDatabase == JdbcDatabase.ORACLE ? ":" : "/";
				connectionString += dbDelimiter + databaseName;
			}
		}
		if (!StringUtils.hasText(hostName)) {
			hostName = "localhost";
		}
		return connectionString.replace("HOST_NAME", hostName);
	}

	private void manageGaeXml(final OrmProvider ormProvider, final JdbcDatabase jdbcDatabase, final String applicationId) {
		final String appenginePath = projectOperations.getPathResolver().getIdentifier(Path.SRC_MAIN_WEBAPP, "WEB-INF/appengine-web.xml");
		final boolean appenginePathExists = fileManager.exists(appenginePath);

		final String loggingPropertiesPath = projectOperations.getPathResolver().getIdentifier(Path.SRC_MAIN_WEBAPP, "WEB-INF/logging.properties");
		final boolean loggingPropertiesPathExists = fileManager.exists(loggingPropertiesPath);

		if (jdbcDatabase != JdbcDatabase.GOOGLE_APP_ENGINE) {
			if (appenginePathExists) {
				fileManager.delete(appenginePath, "database is " + jdbcDatabase.name());
			}
			if (loggingPropertiesPathExists) {
				fileManager.delete(loggingPropertiesPath, "database is " + jdbcDatabase.name());
			}
			return;
		}

		final InputStream in;
		if (appenginePathExists) {
			in = fileManager.getInputStream(appenginePath);
		} else {
			in = TemplateUtils.getTemplate(getClass(), "appengine-web-template.xml");
			Assert.notNull(in, "Could not acquire appengine-web.xml template");
		}
		final Document appengine = XmlUtils.readXml(in);

		final Element root = appengine.getDocumentElement();
		final Element applicationElement = XmlUtils.findFirstElement("/appengine-web-app/application", root);
		final String textContent = StringUtils.hasText(applicationId) ? applicationId : getProjectName();
		if (!textContent.equals(applicationElement.getTextContent())) {
			applicationElement.setTextContent(StringUtils.hasText(applicationId) ? applicationId : getProjectName());
			fileManager.createOrUpdateTextFileIfRequired(appenginePath, XmlUtils.nodeToString(appengine), false);
			LOGGER.warning("Please update your database details in src/main/resources/META-INF/persistence.xml.");
		}

		if (!loggingPropertiesPathExists) {
			try {
				final InputStream templateInputStream = TemplateUtils.getTemplate(getClass(), "logging.properties");
				FileCopyUtils.copy(templateInputStream, fileManager.createFile(loggingPropertiesPath).getOutputStream());
			} catch (final IOException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private void updateDatabaseProperties(final OrmProvider ormProvider, final JdbcDatabase jdbcDatabase, final String hostName, final String databaseName, String userName, final String password) {
		final String databasePath = getDatabasePropertiesPath();
		final boolean databaseExists = fileManager.exists(databasePath);

		if (ormProvider == OrmProvider.DATANUCLEUS || ormProvider == OrmProvider.DATANUCLEUS_2) {
			if (databaseExists) {
				fileManager.delete(databasePath, "ORM provider is " + ormProvider.name());
			}
			return;
		}

		final Properties props = getProperties(databasePath, databaseExists, "database-template.properties");

		final String connectionString = getConnectionString(jdbcDatabase, hostName, databaseName);
		if (jdbcDatabase.getKey().equals("HYPERSONIC") || jdbcDatabase == JdbcDatabase.H2_IN_MEMORY || jdbcDatabase == JdbcDatabase.SYBASE) {
			userName = StringUtils.hasText(userName) ? userName : "sa";
		}

		boolean hasChanged = !props.get(DATABASE_DRIVER).equals(jdbcDatabase.getDriverClassName());
		hasChanged |= !props.get(DATABASE_URL).equals(connectionString);
		hasChanged |= !props.get(DATABASE_USERNAME).equals(StringUtils.trimToEmpty(userName));
		hasChanged |= !props.get(DATABASE_PASSWORD).equals(StringUtils.trimToEmpty(password));
		if (!hasChanged) {
			// No changes from existing database configuration so exit now
			return;
		}

		// Write changes to database.properties file
		props.put(DATABASE_URL, connectionString);
		props.put(DATABASE_DRIVER, jdbcDatabase.getDriverClassName());
		props.put(DATABASE_USERNAME, StringUtils.trimToEmpty(userName));
		props.put(DATABASE_PASSWORD, StringUtils.trimToEmpty(password));

		OutputStream outputStream = null;
		try {
			final MutableFile mutableFile = databaseExists ? fileManager.updateFile(databasePath) : fileManager.createFile(databasePath);
			outputStream = mutableFile.getOutputStream();
			props.store(outputStream, "Updated at " + new Date());
		} catch (final IOException e) {
			throw new IllegalStateException(e);
		} finally {
			IOUtils.closeQuietly(outputStream);
		}

		// Log message to console
		switch (jdbcDatabase) {
			case ORACLE:
			case DB2_EXPRESS_C:
			case DB2_400:
				LOGGER.warning("The " + jdbcDatabase.name() + " JDBC driver is not available in public maven repositories. Please adjust the pom.xml dependency to suit your needs");
				break;
			case POSTGRES:
			case DERBY_EMBEDDED:
			case DERBY_CLIENT:
			case MSSQL:
			case SYBASE:
			case MYSQL:
				LOGGER.warning("Please update your database details in src/main/resources/META-INF/spring/database.properties.");
				break;
		}
	}

	private void updateDbdcConfigProperties(final OrmProvider ormProvider, final JdbcDatabase jdbcDatabase, final String hostName, final String userName, final String password, final String persistenceUnit) {
		final String configPath = projectOperations.getPathResolver().getIdentifier(Path.SRC_MAIN_RESOURCES, persistenceUnit + ".properties");
		final boolean configExists = fileManager.exists(configPath);

		if (jdbcDatabase != JdbcDatabase.DATABASE_DOT_COM) {
			if (configExists) {
				fileManager.delete(configPath, "database is " + jdbcDatabase.name());
			}
			return;
		}

		final String connectionString = getConnectionString(jdbcDatabase, hostName, null /*databaseName*/).replace("USER_NAME", StringUtils.defaultIfEmpty(userName, "${userName}")).replace("PASSWORD", StringUtils.defaultIfEmpty(password, "${password}"));
		final Properties props = getProperties(configPath, configExists, "database-dot-com-template.properties");
		
		final boolean hasChanged = !props.get("url").equals(StringUtils.trimToEmpty(connectionString));
		if (!hasChanged) {
			return;
		}

		props.put("url", StringUtils.trimToEmpty(connectionString));

		OutputStream outputStream = null;
		try {
			final MutableFile mutableFile = configExists ? fileManager.updateFile(configPath) : fileManager.createFile(configPath);
			outputStream = mutableFile.getOutputStream();
			props.store(outputStream, "Updated at " + new Date());
		} catch (final IOException e) {
			throw new IllegalStateException(e);
		} finally {
			IOUtils.closeQuietly(outputStream);
		}

		LOGGER.warning("Please update your database details in src/main/resources/" + persistenceUnit + ".properties.");
	}

	private Properties getProperties(final String path, final boolean exists, final String templateFilename) {
		final Properties props = new Properties();
		InputStream inputStream = null;
		try {
			if (exists) {
				inputStream = fileManager.getInputStream(path);
			} else {
				inputStream = TemplateUtils.getTemplate(getClass(), templateFilename);
				Assert.notNull(inputStream, "Could not acquire " + templateFilename);
			}
			props.load(inputStream);
		} catch (final IOException e) {
			throw new IllegalStateException(e);
		} finally {
			IOUtils.closeQuietly(inputStream);
		}
		return props;
	}

	private void updateLog4j(final OrmProvider ormProvider) {
		final String log4jPath = projectOperations.getPathResolver().getIdentifier(Path.SRC_MAIN_RESOURCES, "log4j.properties");
		if (fileManager.exists(log4jPath)) {
			final MutableFile log4jMutableFile = fileManager.updateFile(log4jPath);
			final Properties props = new Properties();
			OutputStream outputStream = null;
			try {
				props.load(log4jMutableFile.getInputStream());
				final String dnKey = "log4j.category.DataNucleus";
				if (ormProvider == OrmProvider.DATANUCLEUS && !props.containsKey(dnKey)) {
					outputStream = log4jMutableFile.getOutputStream();
					props.put(dnKey, "WARN");
					props.store(outputStream, "Updated at " + new Date());
				} else if (ormProvider != OrmProvider.DATANUCLEUS && props.containsKey(dnKey)) {
					outputStream = log4jMutableFile.getOutputStream();
					props.remove(dnKey);
					props.store(outputStream, "Updated at " + new Date());
				}
			} catch (final IOException e) {
				throw new IllegalStateException(e);
			} finally {
				IOUtils.closeQuietly(outputStream);
			}
		}
	}

	private void updatePomProperties(final Element configuration, final OrmProvider ormProvider, final JdbcDatabase jdbcDatabase) {
		final List<Element> databaseProperties = XmlUtils.findElements(getDbXPath(jdbcDatabase) + "/properties/*", configuration);
		for (final Element property : databaseProperties) {
			projectOperations.addProperty(new Property(property));
		}

		final List<Element> providerProperties = XmlUtils.findElements(getProviderXPath(ormProvider) + "/properties/*", configuration);
		for (final Element property : providerProperties) {
			projectOperations.addProperty(new Property(property));
		}
	}

	/**
	 * Updates the POM with the dependencies required for the given database and
	 * ORM provider, removing any other persistence-related dependencies
	 * 
	 * @param configuration
	 * @param ormProvider
	 * @param jdbcDatabase
	 * @param databaseXPath
	 * @param providersXPath
	 */
	private void updateDependencies(final Element configuration, final OrmProvider ormProvider, final JdbcDatabase jdbcDatabase, final String databaseXPath, final String providersXPath) {
		final List<Dependency> requiredDependencies = new ArrayList<Dependency>();

		final List<Element> databaseDependencies = XmlUtils.findElements(getDbXPath(jdbcDatabase) + "/dependencies/dependency", configuration);
		for (final Element dependencyElement : databaseDependencies) {
			requiredDependencies.add(new Dependency(dependencyElement));
		}

		final List<Element> ormDependencies = XmlUtils.findElements(getProviderXPath(ormProvider) + "/dependencies/dependency", configuration);
		for (final Element dependencyElement : ormDependencies) {
			requiredDependencies.add(new Dependency(dependencyElement));
		}

		// Hard coded to JPA & Hibernate Validator for now
		final List<Element> jpaDependencies = XmlUtils.findElements("/configuration/persistence/provider[@id = 'JPA']/dependencies/dependency", configuration);
		for (final Element dependencyElement : jpaDependencies) {
			requiredDependencies.add(new Dependency(dependencyElement));
		}

		final List<Element> springDependencies = XmlUtils.findElements("/configuration/spring/dependencies/dependency", configuration);
		for (final Element dependencyElement : springDependencies) {
			requiredDependencies.add(new Dependency(dependencyElement));
		}

		// Remove redundant dependencies
		final List<Dependency> redundantDependencies = new ArrayList<Dependency>();
		redundantDependencies.addAll(getDependencies(databaseXPath, configuration));
		redundantDependencies.addAll(getDependencies(providersXPath, configuration));
		// Don't remove any we actually need
		redundantDependencies.removeAll(requiredDependencies);
		
		// Update the POM
		projectOperations.addDependencies(requiredDependencies);
		projectOperations.removeDependencies(redundantDependencies);
	}

	private String getProjectName() {
		return ((ProjectMetadata) metadataService.get(ProjectMetadata.getProjectIdentifier())).getProjectName();
	}

	private void updateRepositories(final Element configuration, final OrmProvider ormProvider, final JdbcDatabase jdbcDatabase) {
		final List<Repository> repositories = new ArrayList<Repository>();

		final List<Element> databaseRepositories = XmlUtils.findElements(getDbXPath(jdbcDatabase) + "/repositories/repository", configuration);
		for (final Element repositoryElement : databaseRepositories) {
			repositories.add(new Repository(repositoryElement));
		}

		final List<Element> ormRepositories = XmlUtils.findElements(getProviderXPath(ormProvider) + "/repositories/repository", configuration);
		for (final Element repositoryElement : ormRepositories) {
			repositories.add(new Repository(repositoryElement));
		}

		final List<Element> jpaRepositories = XmlUtils.findElements("/configuration/persistence/provider[@id='JPA']/repositories/repository", configuration);
		for (final Element repositoryElement : jpaRepositories) {
			repositories.add(new Repository(repositoryElement));
		}

		// Add all new repositories to pom.xml
		projectOperations.addRepositories(repositories);
	}

	private void updatePluginRepositories(final Element configuration, final OrmProvider ormProvider, final JdbcDatabase jdbcDatabase) {
		final List<Repository> pluginRepositories = new ArrayList<Repository>();

		final List<Element> databasePluginRepositories = XmlUtils.findElements(getDbXPath(jdbcDatabase) + "/pluginRepositories/pluginRepository", configuration);
		for (final Element pluginRepositoryElement : databasePluginRepositories) {
			pluginRepositories.add(new Repository(pluginRepositoryElement));
		}

		final List<Element> ormPluginRepositories = XmlUtils.findElements(getProviderXPath(ormProvider) + "/pluginRepositories/pluginRepository", configuration);
		for (final Element pluginRepositoryElement : ormPluginRepositories) {
			pluginRepositories.add(new Repository(pluginRepositoryElement));
		}

		// Add all new plugin repositories to pom.xml
		projectOperations.addPluginRepositories(pluginRepositories);
	}

	private void updateFilters(final Element configuration, final OrmProvider ormProvider, final JdbcDatabase jdbcDatabase, final String databaseXPath, final String providersXPath) {
		// Remove redundant filters
		final List<Filter> redundantFilters = new ArrayList<Filter>();
		redundantFilters.addAll(getFilters(databaseXPath, configuration));
		redundantFilters.addAll(getFilters(providersXPath, configuration));
		for (final Filter filter : redundantFilters) {
			projectOperations.removeFilter(filter);
		}
		
		// Add required filters
		final List<Filter> filters = new ArrayList<Filter>();

		final List<Element> databaseFilters = XmlUtils.findElements(getDbXPath(jdbcDatabase) + "/filters/filter", configuration);
		for (final Element filterElement : databaseFilters) {
			filters.add(new Filter(filterElement));
		}

		final List<Element> ormFilters = XmlUtils.findElements(getProviderXPath(ormProvider) + "/filters/filter", configuration);
		for (final Element filterElement : ormFilters) {
			filters.add(new Filter(filterElement));
		}

		for (final Filter filter : filters) {
			projectOperations.addFilter(filter);
		}
	}

	private void updateResources(final Element configuration, final OrmProvider ormProvider, final JdbcDatabase jdbcDatabase, final String databaseXPath, final String providersXPath) {
		// Remove redundant resources
		final List<Resource> redundantResources = new ArrayList<Resource>();
		redundantResources.addAll(getResources(databaseXPath, configuration));
		redundantResources.addAll(getResources(providersXPath, configuration));
		for (final Resource resource : redundantResources) {
			projectOperations.removeResource(resource);
		}
		
		// Add required resources
		final List<Resource> resources = new ArrayList<Resource>();

		final List<Element> databaseResources = XmlUtils.findElements(getDbXPath(jdbcDatabase) + "/resources/resource", configuration);
		for (final Element resourceElement : databaseResources) {
			resources.add(new Resource(resourceElement));
		}

		final List<Element> ormResources = XmlUtils.findElements(getProviderXPath(ormProvider) + "/resources/resource", configuration);
		for (final Element resourceElement : ormResources) {
			resources.add(new Resource(resourceElement));
		}

		for (final Resource resource : resources) {
			projectOperations.addResource(resource);
		}
	}

	private void updateBuildPlugins(final Element configuration, final OrmProvider ormProvider, final JdbcDatabase jdbcDatabase, final String databaseXPath, final String providersXPath) {
		// Identify the required plugins
		final List<Plugin> requiredPlugins = new ArrayList<Plugin>();
		
		final List<Element> databasePlugins = XmlUtils.findElements(getDbXPath(jdbcDatabase) + "/plugins/plugin", configuration);
		for (final Element pluginElement : databasePlugins) {
			requiredPlugins.add(new Plugin(pluginElement));
		}
		
		final List<Element> ormPlugins = XmlUtils.findElements(getProviderXPath(ormProvider) + "/plugins/plugin", configuration);
		for (final Element pluginElement : ormPlugins) {
			requiredPlugins.add(new Plugin(pluginElement));
		}
		
		// Identify any redundant plugins
		final List<Plugin> redundantPlugins = new ArrayList<Plugin>();
		redundantPlugins.addAll(getPlugins(databaseXPath, configuration));
		redundantPlugins.addAll(getPlugins(providersXPath, configuration));
		// Don't remove any that are still required
		redundantPlugins.removeAll(requiredPlugins);
		
		// Update the POM
		projectOperations.addBuildPlugins(requiredPlugins);
		projectOperations.removeBuildPlugins(redundantPlugins);

		if (jdbcDatabase == JdbcDatabase.GOOGLE_APP_ENGINE) {
			updateEclipsePlugin(true);
			updateDataNucleusPlugin(true);
			projectOperations.updateDependencyScope(JSTL_IMPL_DEPENDENCY, DependencyScope.PROVIDED);
		}
	}

	private void updateEclipsePlugin(final boolean addGaeSettingsToPlugin) {
		final String pom = projectOperations.getPathResolver().getIdentifier(Path.ROOT, POM_XML);
		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom));
		final Element root = document.getDocumentElement();
		final Collection<String> changes = new ArrayList<String>();

		// Manage GAE buildCommand
		final Element additionalBuildcommandsElement = XmlUtils.findFirstElement("/project/build/plugins/plugin[artifactId = 'maven-eclipse-plugin']/configuration/additionalBuildcommands", root);
		Assert.notNull(additionalBuildcommandsElement, "additionalBuildcommands element of the maven-eclipse-plugin required");
		final String gaeBuildCommandName = "com.google.appengine.eclipse.core.enhancerbuilder";
		Element gaeBuildCommandElement = XmlUtils.findFirstElement("buildCommand[name = '" + gaeBuildCommandName + "']", additionalBuildcommandsElement);
		if (addGaeSettingsToPlugin && gaeBuildCommandElement == null) {
			final Element nameElement = document.createElement("name");
			nameElement.setTextContent(gaeBuildCommandName);
			gaeBuildCommandElement = document.createElement("buildCommand");
			gaeBuildCommandElement.appendChild(nameElement);
			additionalBuildcommandsElement.appendChild(gaeBuildCommandElement);
			changes.add("added GAE buildCommand to maven-eclipse-plugin");
		} else if (!addGaeSettingsToPlugin && gaeBuildCommandElement != null) {
			additionalBuildcommandsElement.removeChild(gaeBuildCommandElement);
			changes.add("removed GAE buildCommand from maven-eclipse-plugin");
		}

		// Manage GAE projectnature
		final Element additionalProjectnaturesElement = XmlUtils.findFirstElement("/project/build/plugins/plugin[artifactId = 'maven-eclipse-plugin']/configuration/additionalProjectnatures", root);
		Assert.notNull(additionalProjectnaturesElement, "additionalProjectnatures element of the maven-eclipse-plugin required");
		final String gaeProjectnatureName = "com.google.appengine.eclipse.core.gaeNature";
		Element gaeProjectnatureElement = XmlUtils.findFirstElement("projectnature[text() = '" + gaeProjectnatureName + "']", additionalProjectnaturesElement);
		if (addGaeSettingsToPlugin && gaeProjectnatureElement == null) {
			gaeProjectnatureElement = new XmlElementBuilder("projectnature", document).setText(gaeProjectnatureName).build();
			additionalProjectnaturesElement.appendChild(gaeProjectnatureElement);
			changes.add("added GAE projectnature to maven-eclipse-plugin");
		} else if (!addGaeSettingsToPlugin && gaeProjectnatureElement != null) {
			additionalProjectnaturesElement.removeChild(gaeProjectnatureElement);
			changes.add("removed GAE projectnature from maven-eclipse-plugin");
		}
		
		if (!changes.isEmpty()) {
			final String changesMessage = StringUtils.collectionToDelimitedString(changes, "; ");
			fileManager.createOrUpdateTextFileIfRequired(pom, XmlUtils.nodeToString(document), changesMessage, false);
		}
	}

	private void updateDataNucleusPlugin(final boolean addToPlugin) {
		final String pom = projectOperations.getPathResolver().getIdentifier(Path.ROOT, POM_XML);
		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom));
		final Element root = document.getDocumentElement();
	
		// Manage mappingExcludes
		final Element configurationElement = XmlUtils.findFirstElement("/project/build/plugins/plugin[artifactId = 'maven-datanucleus-plugin']/configuration", root);
		if (configurationElement == null) {
			return;
		}

		String descriptionOfChange = "";
		Element mappingExcludesElement = XmlUtils.findFirstElement("mappingExcludes", configurationElement);
		if (addToPlugin && mappingExcludesElement == null) {
			mappingExcludesElement = new XmlElementBuilder("mappingExcludes", document).setText("**/CustomRequestFactoryServlet.class, **/GaeAuthFilter.class").build();
			configurationElement.appendChild(mappingExcludesElement);
			descriptionOfChange = "added GAEAuthFilter mappingExcludes to maven-datanuclueus-plugin";
		} else if (!addToPlugin && mappingExcludesElement != null) {
			configurationElement.removeChild(mappingExcludesElement);
			descriptionOfChange = "removed GAEAuthFilter mappingExcludes from maven-datanuclueus-plugin";
		}

		fileManager.createOrUpdateTextFileIfRequired(pom, XmlUtils.nodeToString(document), descriptionOfChange, false);
	}
	
	private List<JdbcDatabase> getUnwantedDatabases(final JdbcDatabase jdbcDatabase) {
		final List<JdbcDatabase> unwantedDatabases = new ArrayList<JdbcDatabase>();
		for (final JdbcDatabase database : JdbcDatabase.values()) {
			if (!database.getKey().equals(jdbcDatabase.getKey()) && !database.getDriverClassName().equals(jdbcDatabase.getDriverClassName())) {
				unwantedDatabases.add(database);
			}
		}
		return unwantedDatabases;
	}
	
	private List<OrmProvider> getUnwantedOrmProviders(final OrmProvider ormProvider) {
		final List<OrmProvider> unwantedOrmProviders = new ArrayList<OrmProvider>(Arrays.asList(OrmProvider.values()));
		unwantedOrmProviders.remove(ormProvider);
		return unwantedOrmProviders;
	}

	private List<Dependency> getDependencies(final String xPathExpression, final Element configuration) {
		final List<Dependency> dependencies = new ArrayList<Dependency>();
		for (final Element dependencyElement : XmlUtils.findElements(xPathExpression + "/dependencies/dependency", configuration)) {
			final Dependency dependency = new Dependency(dependencyElement);
			if (projectOperations.getProjectMetadata().isGwtEnabled() && dependency.getGroupId().equals("com.google.appengine") && dependency.getArtifactId().equals("appengine-api-1.0-sdk")) {
				continue;
			}
			dependencies.add(dependency);
		}
		return dependencies;
	}

	private List<Filter> getFilters(final String xPathExpression, final Element configuration) {
		final List<Filter> filters = new ArrayList<Filter>();
		for (final Element filterElement : XmlUtils.findElements(xPathExpression + "/filters/filter", configuration)) {
			filters.add(new Filter(filterElement));
		}
		return filters;
	}

	private List<Plugin> getPlugins(final String xPathExpression, final Element configuration) {
		final List<Plugin> buildPlugins = new ArrayList<Plugin>();
		for (final Element pluginElement : XmlUtils.findElements(xPathExpression + "/plugins/plugin", configuration)) {
			buildPlugins.add(new Plugin(pluginElement));
		}
		return buildPlugins;
	}

	private List<Resource> getResources(final String xPathExpression, final Element configuration) {
		final List<Resource> resources = new ArrayList<Resource>();
		for (final Element resourceElement : XmlUtils.findElements(xPathExpression + "/resources/resource", configuration)) {
			resources.add(new Resource(resourceElement));
		}
		return resources;
	}

	private String getDbXPath(final JdbcDatabase jdbcDatabase) {
		return "/configuration/databases/database[@id = '" + jdbcDatabase.getKey() + "']";
	}

	private String getDbXPath(final List<JdbcDatabase> databases) {
		final StringBuilder builder = new StringBuilder("/configuration/databases/database[");
		for (int i = 0, n = databases.size(); i < n; i++) {
			builder.append("@id = '");
			builder.append(databases.get(i).getKey());
			builder.append("'");
			if (i < n - 1) {
				builder.append(" or ");
			}
		}
		builder.append("]");
		return builder.toString();
	}

	private String getProviderXPath(final OrmProvider provider) {
		return "/configuration/ormProviders/provider[@id = '" + provider.name() + "']";
	}

	private String getProviderXPath(final List<OrmProvider> ormProviders) {
		final StringBuilder builder = new StringBuilder("/configuration/ormProviders/provider[");
		for (int i = 0, n = ormProviders.size(); i < n; i++) {
			builder.append("@id = '");
			builder.append(ormProviders.get(i).name());
			builder.append("'");
			if (i < n - 1) {
				builder.append(" or ");
			}
		}
		builder.append("]");
		return builder.toString();
	}

	private Element createPropertyElement(final String name, final String value, final Document document) {
		final Element property = document.createElement("property");
		property.setAttribute("name", name);
		property.setAttribute("value", value);
		return property;
	}

	private Element createRefElement(final String name, final String value, final Document document) {
		final Element property = document.createElement("property");
		property.setAttribute("name", name);
		property.setAttribute("ref", value);
		return property;
	}

	private SortedSet<String> getPropertiesFromDataNucleusConfiguration() {
		final String persistenceXmlPath = projectOperations.getPathResolver().getIdentifier(Path.SRC_MAIN_RESOURCES, "META-INF/persistence.xml");
		if (!fileManager.exists(persistenceXmlPath)) {
			throw new IllegalStateException("Failed to find " + persistenceXmlPath);
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(persistenceXmlPath));
		final Element root = document.getDocumentElement();

		final List<Element> propertyElements = XmlUtils.findElements("/persistence/persistence-unit/properties/property", root);
		Assert.notEmpty(propertyElements, "Failed to find property elements in " + persistenceXmlPath);
		final SortedSet<String> properties = new TreeSet<String>();

		for (final Element propertyElement : propertyElements) {
			final String key = propertyElement.getAttribute("name");
			final String value = propertyElement.getAttribute("value");
			if ("datanucleus.ConnectionDriverName".equals(key)) {
				properties.add("datanucleus.ConnectionDriverName = " + value);
			}
			if ("datanucleus.ConnectionURL".equals(key)) {
				properties.add("datanucleus.ConnectionURL = " + value);
			}
			if ("datanucleus.ConnectionUserName".equals(key)) {
				properties.add("datanucleus.ConnectionUserName = " + value);
			}
			if ("datanucleus.ConnectionPassword".equals(key)) {
				properties.add("datanucleus.ConnectionPassword = " + value);
			}

			if (properties.size() == 4) {
				// All required properties have been found so ignore rest of elements
				break;
			}
		}
		return properties;
	}
}
