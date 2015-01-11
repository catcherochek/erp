package org.meridor.erp.plugins;

import org.meridor.erp.annotation.Controller;
import org.meridor.stecker.PluginException;
import org.meridor.stecker.PluginLoader;
import org.meridor.stecker.PluginMetadata;
import org.meridor.stecker.PluginRegistry;
import org.meridor.stecker.interfaces.Dependency;
import org.meridor.stecker.interfaces.DependencyProblem;
import org.meridor.steve.Job;
import org.meridor.steve.annotations.JobCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionValidationException;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.Repository;

import javax.persistence.Entity;
import java.beans.Introspector;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.springframework.beans.factory.support.AbstractBeanDefinition.AUTOWIRE_BY_TYPE;

public class PluginLoaderBeanDefinitionRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor, ApplicationEventPublisherAware, ApplicationListener<ContextRefreshedEvent> {

    public static final String LIST_SEPARATOR = ", ";

    private static final Logger LOG = LoggerFactory.getLogger(PluginLoaderBeanDefinitionRegistryPostProcessor.class);

    private PluginRegistry pluginRegistry;

    private ApplicationEventPublisher eventPublisher;

    private UberClassLoader beanClassLoader = new UberClassLoader();

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        pluginRegistry = loadPlugins();
        List<Class> extensionPoints = pluginRegistry.getExtensionPoints();

        for (Class extensionPoint : extensionPoints) {
            List<Class> implementations = pluginRegistry.getImplementations(extensionPoint);
            LOG.debug(String.format(
                    "Found %d implementations for %s extension point: [%s]",
                    implementations.size(),
                    extensionPoint.getCanonicalName(),
                    implementations.stream().map(Class::getCanonicalName).collect(Collectors.joining(LIST_SEPARATOR))
            ));
            for (Class implementation : implementations) {
                processImplementation(registry, implementation);
            }
        }

        List<Path> resources = pluginRegistry.getResources();
        LOG.debug(String.format(
                "Found %d resource files: [%s]",
                resources.size(),
                resources.stream().map(Path::toString).collect(Collectors.joining(LIST_SEPARATOR))
        ));

    }

    private void processImplementation(BeanDefinitionRegistry registry, Class implementation) {
        if (!implementation.isInterface() && !Modifier.isAbstract(implementation.getModifiers())) {
            processRegularClass(registry, implementation);
            addToBeanClassLoader(implementation);
        } else if (implementation.isInterface() && Repository.class.isAssignableFrom(implementation)) {
            processRepositoryInterface(registry, implementation);
            addToBeanClassLoader(implementation);
        } else {
            LOG.warn(String.format("Skipping unknown extension point %s", implementation.getCanonicalName()));
        }
    }

    private void processRegularClass(BeanDefinitionRegistry registry, Class implementation) {
        LOG.debug(String.format("Registering bean definition for implementation class %s", implementation.getCanonicalName()));
        String beanName = implementation.getCanonicalName();
        GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
        beanDefinition.setBeanClass(implementation);
        beanDefinition.setAutowireMode(AUTOWIRE_BY_TYPE);
        registry.registerBeanDefinition(beanName, beanDefinition);
    }

    private void processRepositoryInterface(BeanDefinitionRegistry registry, Class implementation) {
        LOG.debug(String.format("Registering bean definition for repository interface %s", implementation.getCanonicalName()));
        String beanName = Introspector.decapitalize(implementation.getSimpleName());
        GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
        beanDefinition.setBeanClass(JpaRepositoryFactoryBean.class);
        MutablePropertyValues mutablePropertyValues = new MutablePropertyValues();
        mutablePropertyValues.add("repositoryInterface", implementation);
        beanDefinition.setPropertyValues(mutablePropertyValues);
        registry.registerBeanDefinition(beanName, beanDefinition);
    }

    private void addToBeanClassLoader(Class implementation) {
        LOG.debug(String.format("Adding implementation %s to UberClassLoader", implementation.getCanonicalName()));
        beanClassLoader.addClass(implementation);
    }

    private PluginRegistry loadPlugins() {
        Path pluginsDirectory = getPluginsDirectory();
        Class[] extensionPoints = getExtensionPoints();
        String[] resourcesPatterns = getResourcesPatterns();
        LOG.info(String.format(
                "Loading plugins from %s with extension points = [%s] and resource patterns = [%s]",
                pluginsDirectory,
                Arrays.stream(extensionPoints).map(Class::getCanonicalName).collect(Collectors.joining(LIST_SEPARATOR)),
                Arrays.stream(resourcesPatterns).collect(Collectors.joining(", "))
        ));

        try {
            return loadPlugins(pluginsDirectory, extensionPoints, resourcesPatterns);
        } catch (PluginException e) {
            handlePluginException(e);
            throw new BeanDefinitionValidationException("Failed to load plugins", e);
        }
    }

    protected PluginRegistry loadPlugins(Path pluginsDirectory, Class[] extensionPoints, String[] resourcesPatterns) throws PluginException {
        return PluginLoader
                .withPluginDirectory(pluginsDirectory)
                .withExtensionPoints(extensionPoints)
                .withResourcesPatterns(resourcesPatterns)
                .load();
    }

    private void handlePluginException(PluginException e) {
        Optional<PluginMetadata> possiblePluginMetadata = e.getPluginMetadata();
        if (possiblePluginMetadata.isPresent()) {
            PluginMetadata pluginMetadata = possiblePluginMetadata.get();
            String pluginName = pluginMetadata.getName();
            String pluginVersion = pluginMetadata.getVersion();
            LOG.error(String.format("Failed to load plugin %s-%s", pluginName, pluginVersion));
        }

        Optional<DependencyProblem> possibleDependencyProblem = e.getDependencyProblem();
        if (possibleDependencyProblem.isPresent()) {
            DependencyProblem dependencyProblem = possibleDependencyProblem.get();
            LOG.error("Dependency problem detected");

            if (!dependencyProblem.getMissingDependencies().isEmpty()) {
                String missingDependencies = dependencyProblem.getMissingDependencies().stream()
                        .map(Dependency::toString)
                        .collect(Collectors.joining(LIST_SEPARATOR));
                LOG.error(String.format("The following dependencies are missing: [%s]", missingDependencies));
            }

            if (!dependencyProblem.getConflictingDependencies().isEmpty()) {
                String conflictingDependencies = dependencyProblem.getConflictingDependencies().stream()
                        .map(Dependency::toString)
                        .collect(Collectors.joining(LIST_SEPARATOR));
                LOG.error(String.format("This plugin conflicts with the following dependencies: [%s]", conflictingDependencies));
            }
        }
    }

    protected Path getPluginsDirectory() {
        return Paths.get(System.getProperty("user.dir"), "plugins");
    }

    private Class[] getExtensionPoints() {
        return new Class[]{
                //Jobs
                Job.class,
                org.meridor.steve.annotations.Job.class,
                JobCollection.class,

                //UI
                Controller.class,

                //Persistence
                Entity.class,
                Repository.class,

                //Spring configuration classes
                Configuration.class
        };
    }

    private String[] getResourcesPatterns() {
        return new String[]{
                "glob:**/*.fxml"
        };
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        beanFactory.setBeanClassLoader(beanClassLoader);
        beanFactory.addBeanPostProcessor(new PluginClassLoaderBeanPostProcessor(beanClassLoader));
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.eventPublisher = applicationEventPublisher;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        eventPublisher.publishEvent(new PluginsLoadedEvent(this, pluginRegistry));
    }

}
