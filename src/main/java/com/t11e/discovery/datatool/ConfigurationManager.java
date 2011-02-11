package com.t11e.discovery.datatool;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.sql.DataSource;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentFactory;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.GrantedAuthorityImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.memory.InMemoryDaoImpl;
import org.springframework.security.core.userdetails.memory.UserMap;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

@Component("ConfigurationManager")
public class ConfigurationManager
{
  private static final List<GrantedAuthority> DEFAULT_ROLES =
      Arrays.asList((GrantedAuthority) new GrantedAuthorityImpl("ROLE_USER"));
  private File configurationFile = new File("discovery_datatool.xml");
  private File workingDirectory;
  private boolean exitOnInvalidConfigAtStartup;
  private ConfigurableApplicationContext currentContext;
  private BypassAuthenticationFilter bypassAuthenticationFilter;
  private InMemoryDaoImpl userDetailsService;

  @PostConstruct
  public void onPostConstruct()
  {
    try
    {
      loadConfiguration(new FileInputStream(configurationFile), false);
      if (workingDirectory == null)
      {
        workingDirectory = new File(".").getCanonicalFile();
      }
    }
    catch (final RuntimeException e)
    {
      exitOnStartupIfConfigured(e);
      throw e;
    }
    catch (final FileNotFoundException e)
    {
      exitOnStartupIfConfigured(e);
      throw new RuntimeException(e);
    }
    catch (final IOException e)
    {
      exitOnStartupIfConfigured(e);
      throw new RuntimeException(e);
    }
  }

  // TODO This is a hack so we don't need to dive into the Jetty/Spring
  // lifecycle stuff. It allows us to terminate the tool when running it from
  // a standalone jar if the config is missing or invalid.
  private void exitOnStartupIfConfigured(final Throwable e)
  {
    if (exitOnInvalidConfigAtStartup)
    {
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }

  public boolean loadConfiguration(
    final InputStream is,
    final boolean persist)
  {
    boolean persisted = false;
    byte[] config;
    try
    {
      config = IOUtils.toByteArray(is);
    }
    catch (final IOException e)
    {
      throw new RuntimeException(e);
    }
    IOUtils.closeQuietly(is);
    final GenericApplicationContext newContext = createApplicationContext(new ByteArrayInputStream(config));
    newContext.start();
    synchronized (this)
    {
      if (persist)
      {
        persisted = swapConfigFiles(config);
      }
      if (currentContext != null)
      {
        currentContext.stop();
        currentContext.close();
        currentContext = null;
      }
      applyAccessControl(new ByteArrayInputStream(config));
      currentContext = newContext;
    }
    return persisted;
  }

  private boolean swapConfigFiles(final byte[] config)
  {
    boolean changed = false;
    try
    {
      final File newConfig =
          File.createTempFile(configurationFile.getName(), ".tmp",
            configurationFile.getCanonicalFile().getParentFile());
      FileUtils.writeByteArrayToFile(newConfig, config);
      final File backupFile = new File(configurationFile + ".bak");
      if (configurationFile.exists())
      {
        try
        {
          FileUtils.forceDelete(backupFile);
        }
        catch (final FileNotFoundException e)
        {
          // Ignore
        }
        FileUtils.moveFile(configurationFile, backupFile);
      }
      FileUtils.moveFile(newConfig, configurationFile);
      FileUtils.deleteQuietly(backupFile);
      changed = true;
    }
    catch (final IOException e)
    {
      throw new RuntimeException(e);
    }
    return changed;
  }

  public <T> T getBean(final Class<T> klass)
    throws BeansException
  {
    synchronized (this)
    {
      return currentContext.getBean(klass);
    }
  }

  @SuppressWarnings("unchecked")
  private GenericApplicationContext createApplicationContext(final InputStream is)
  {
    final GenericApplicationContext applicationContext = new GenericApplicationContext();
    final Document document;
    final String ns;
    {
      final Document[] documentHolder = new Document[1];
      final String[] namespaceHolder = new String[1];
      parseConfiguration(is, documentHolder, namespaceHolder);
      document = documentHolder[0];
      ns = namespaceHolder[0];
    }

    if (document.selectSingleNode("/c:config".replace("c:", ns)) == null)
    {
      throw new RuntimeException("Missing root config element. Did you specify a namespace?");
    }

    for (final Node node : (List<Node>) document.selectNodes("/c:config/c:dataSources/c:dataSource".replace("c:", ns)))
    {
      final String name = node.valueOf("@name");
      final Class<DataSource> clazz = loadDataSource(node.valueOf("@class"), node.valueOf("@jar"));
      final BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(clazz);
      for (final Node child : (List<Node>) node.selectNodes("*"))
      {
        builder.addPropertyValue(
          child.getName(), StringUtils.trimToEmpty(child.getText()));
      }
      applicationContext.registerBeanDefinition("dataSource-" + name, builder.getBeanDefinition());
    }

    for (final Node node : (List<Node>) document.selectNodes("/c:config/c:dataSources/c:driver".replace("c:", ns)))
    {
      final String name = node.valueOf("@name");
      final Class<Driver> clazz = loadDataSource(node.valueOf("@class"), node.valueOf("@jar"));
      final BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(SimpleDriverDataSource.class);
      builder.addPropertyValue("driverClass", clazz);
      builder.addPropertyValue("url", node.valueOf("c:url".replace("c:", ns)));
      addPropertyIfExists(builder, "username", node, "c:username".replace("c:", ns));
      addPropertyIfExists(builder, "password", node, "c:password".replace("c:", ns));
      {
        final Map<String, String> properties = new HashMap<String, String>();
        for (final Node child : (List<Node>) node.selectNodes("c:properties/*".replace("c:", ns)))
        {
          properties.put(child.getName(), StringUtils.trimToEmpty(child.getText()));
        }
        if (!properties.isEmpty())
        {
          builder.addPropertyValue("connectionProperties", "");
        }
      }
      applicationContext.registerBeanDefinition("dataSource-" + name, builder.getBeanDefinition());
    }

    for (final Node node : (List<Node>) document.selectNodes("/c:config/c:profiles/c:sqlProfile".replace("c:", ns)))
    {
      final BeanDefinitionBuilder builder = BeanDefinitionBuilder
        .genericBeanDefinition(SqlChangesetProfileService.class);
      builder.addPropertyReference("dataSource", "dataSource-" + node.valueOf("@dataSource"));
      builder.addPropertyValue("createSql", node.valueOf("c:createSql".replace("c:", ns)));
      builder.addPropertyValue("retrieveStartColumn", node.valueOf("c:retrieveSql/@startColumn".replace("c:", ns)));
      builder.addPropertyValue("retrieveEndColumn", node.valueOf("c:retrieveSql/@endColumn".replace("c:", ns)));
      builder.addPropertyValue("retrieveSql", node.valueOf("c:retrieveSql".replace("c:", ns)));
      builder.addPropertyValue("updateSql", node.valueOf("c:updateSql".replace("c:", ns)));
      applicationContext.registerBeanDefinition("profile-" + node.valueOf("@name"), builder.getBeanDefinition());
    }

    {
      final List<ChangesetPublisher> publishers = new ArrayList<ChangesetPublisher>();
      for (final Node node : (List<Node>) document.selectNodes("/c:config/c:publishers/c:sqlPublisher"
        .replace("c:", ns)))
      {
        final List<SqlAction> actions = new ArrayList<SqlAction>();
        for (final Node action : (List<Node>) node.selectNodes("c:action".replace("c:", ns)))
        {
          final BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(SqlAction.class);
          builder.addPropertyValue("action", action.valueOf("@type"));
          builder.addPropertyValue("filter", action.valueOf("@filter"));
          builder
            .addPropertyValue("query", StringUtils.trimToEmpty(action.valueOf("c:query/text()".replace("c:", ns))));
          builder.addPropertyValue("idColumn", action.valueOf("@idColumn"));
          builder.addPropertyValue("jsonColumnNames", action.valueOf("@jsonColumnNames"));
          final String beanName = "SqlAction-" + System.identityHashCode(builder);
          applicationContext.registerBeanDefinition(beanName, builder.getBeanDefinition());
          actions.add(applicationContext.getBean(beanName, SqlAction.class));
        }
        BeanDefinition sqlChangesetExtractor;
        {
          final BeanDefinitionBuilder builder = BeanDefinitionBuilder
            .genericBeanDefinition(SqlChangesetExtractor.class);
          builder.addPropertyReference("dataSource", "dataSource-" + node.valueOf("@dataSource"));
          builder.addPropertyValue("actions", actions);
          sqlChangesetExtractor = builder.getBeanDefinition();
        }
        {
          final BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ChangesetPublisher.class);
          final String name = node.valueOf("@name");
          builder.addPropertyValue("name", name);
          builder.addPropertyReference("changesetProfileService", "profile-" + node.valueOf("@profile"));
          builder.addPropertyValue("changesetExtractor", sqlChangesetExtractor);
          final String beanName = "Publisher-" + name;
          applicationContext.registerBeanDefinition(beanName, builder.getBeanDefinition());
          publishers.add(applicationContext.getBean(beanName, ChangesetPublisher.class));
        }
      }
      {
        final BeanDefinitionBuilder builder = BeanDefinitionBuilder
          .genericBeanDefinition(ChangesetPublisherManager.class);
        builder.addPropertyValue("publishers", publishers);
        applicationContext.registerBeanDefinition("ChangesetPublisherManager", builder.getBeanDefinition());
      }
    }
    applicationContext.refresh();
    return applicationContext;
  }

  @SuppressWarnings("unchecked")
  private void applyAccessControl(final InputStream is)
  {
    final Document document;
    final String ns;
    {
      final Document[] documentHolder = new Document[1];
      final String[] namespaceHolder = new String[1];
      parseConfiguration(is, documentHolder, namespaceHolder);
      document = documentHolder[0];
      ns = namespaceHolder[0];
    }
    bypassAuthenticationFilter.setBypass(false);
    userDetailsService.setUserMap(new UserMap());
    for (final Node node : (List<Node>) document.selectNodes("/c:config/c:accessControl/c:user".replace("c:", ns)))
    {
      userDetailsService.getUserMap().addUser(new User(node.valueOf("@name"),
        node.valueOf("@password"), true, true, true, true, DEFAULT_ROLES));
    }
    {
      final Node accessControl = document.selectSingleNode("/c:config/c:accessControl".replace("c:", ns));
      bypassAuthenticationFilter.setBypass(accessControl == null);
    }
  }

  @SuppressWarnings("unchecked")
  private void parseConfiguration(
    final InputStream is,
    final Document[] documentHolder,
    final String[] namespaceHolder)
  {
    final SAXReader saxReader = new SAXReader(true);
    try
    {
      saxReader.setFeature("http://apache.org/xml/features/validation/schema", true);
    }
    catch (final SAXException e)
    {
      throw new RuntimeException(e);
    }
    saxReader.setEntityResolver(new DataToolEntityResolver());
    final Map<String, String> namespacesByPrefix = CollectionsFactory.makeMap(
      "c1", "http://transparensee.com/schema/datatool-config-1",
      "c2", "http://transparensee.com/schema/datatool-config-2",
      "c3", "http://transparensee.com/schema/datatool-config-3");
    final Map<String, String> namespacesByUri = MapUtils.invertMap(namespacesByPrefix);
    {
      final DocumentFactory factory = new DocumentFactory();
      factory.setXPathNamespaceURIs(namespacesByPrefix);
      saxReader.setDocumentFactory(factory);
    }
    final Document document;
    try
    {
      document = saxReader.read(is);
    }
    catch (final DocumentException e)
    {
      throw new RuntimeException(e);
    }
    final String ns;
    {
      final String prefix = namespacesByUri.get(document.getRootElement().getNamespaceURI());
      ns = prefix == null ? "" : (prefix + ":");
    }
    namespaceHolder[0] = ns;
    documentHolder[0] = document;
  }

  private static void addPropertyIfExists(
    final BeanDefinitionBuilder builder,
    final String name,
    final Node node,
    final String xpath)
  {
    final String value = StringUtils.trimToEmpty(node.valueOf(xpath));
    if (StringUtils.isNotBlank(value))
    {
      builder.addPropertyValue(name, value);
    }
  }

  private <T> Class<T> loadDataSource(
    final String dataSourceClassName,
    final String jarPath)
  {
    URL jarUrl = null;
    try
    {
      if (StringUtils.isNotBlank(jarPath))
      {
        File jarFile = new File(jarPath);
        if (!jarFile.isAbsolute())
        {
          jarFile = new File(workingDirectory, jarPath);
        }
        jarUrl = jarFile.toURI().toURL();
      }
    }
    catch (final MalformedURLException e)
    {
      throw new RuntimeException(e);
    }
    return loadDataSource(dataSourceClassName, jarUrl);
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> loadDataSource(
    final String dataSourceClassName,
    final URL jarUrl)
  {
    Class<T> driverClass;
    try
    {
      if (jarUrl == null)
      {
        driverClass = (Class<T>) Class.forName(dataSourceClassName);
      }
      else
      {
        final URLClassLoader classLoader = new URLClassLoader(new URL[]{jarUrl});
        driverClass = (Class<T>) classLoader.loadClass(dataSourceClassName);
      }
    }
    catch (final ClassCastException e)
    {
      throw new RuntimeException(dataSourceClassName + " is not a DataSource or Driver, from " + jarUrl, e);
    }
    catch (final ClassNotFoundException e)
    {
      throw new RuntimeException("Could not find the DataSource or Driver: " + dataSourceClassName + " from " + jarUrl,
        e);
    }
    return driverClass;
  }

  public void setExitOnInvalidConfigAtStartup(
    final boolean exitOnInvalidConfigAtStartup)
  {
    this.exitOnInvalidConfigAtStartup = exitOnInvalidConfigAtStartup;
  }

  public void setConfigurationFile(final String configurationFile)
  {
    this.configurationFile = new File(configurationFile);
  }

  public void setWorkingDirectory(final String workingDirectory)
  {
    try
    {
      this.workingDirectory = new File(workingDirectory).getCanonicalFile();
    }
    catch (final IOException e)
    {
      throw new RuntimeException(e);
    }
  }

  @Resource(name = "BypassAuthenticationFilter")
  public void setBypassAuthenticationFilter(
    final BypassAuthenticationFilter bypassAuthenticationFilter)
  {
    this.bypassAuthenticationFilter = bypassAuthenticationFilter;
  }

  @Autowired
  public void setInMemoryDaoImpl(final InMemoryDaoImpl inMemoryDaoImpl)
  {
    userDetailsService = inMemoryDaoImpl;
  }
}
