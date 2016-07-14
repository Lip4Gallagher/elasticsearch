/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack;

import java.io.IOException;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.elasticsearch.SpecialPermission;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Binder;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.multibindings.Multibinder;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.license.plugin.Licensing;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.threadpool.ExecutorBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.xpack.action.TransportXPackInfoAction;
import org.elasticsearch.xpack.action.TransportXPackUsageAction;
import org.elasticsearch.xpack.action.XPackInfoAction;
import org.elasticsearch.xpack.action.XPackUsageAction;
import org.elasticsearch.xpack.common.ScriptServiceProxy;
import org.elasticsearch.xpack.common.http.HttpClient;
import org.elasticsearch.xpack.common.http.HttpRequestTemplate;
import org.elasticsearch.xpack.common.http.auth.HttpAuthFactory;
import org.elasticsearch.xpack.common.http.auth.HttpAuthRegistry;
import org.elasticsearch.xpack.common.http.auth.basic.BasicAuth;
import org.elasticsearch.xpack.common.http.auth.basic.BasicAuthFactory;
import org.elasticsearch.xpack.common.text.TextTemplateModule;
import org.elasticsearch.xpack.extensions.XPackExtension;
import org.elasticsearch.xpack.extensions.XPackExtensionsService;
import org.elasticsearch.xpack.graph.Graph;
import org.elasticsearch.xpack.monitoring.Monitoring;
import org.elasticsearch.xpack.monitoring.MonitoringSettings;
import org.elasticsearch.xpack.notification.Notification;
import org.elasticsearch.xpack.notification.email.Account;
import org.elasticsearch.xpack.notification.email.support.BodyPartSource;
import org.elasticsearch.xpack.rest.action.RestXPackInfoAction;
import org.elasticsearch.xpack.rest.action.RestXPackUsageAction;
import org.elasticsearch.xpack.security.InternalClient;
import org.elasticsearch.xpack.security.Security;
import org.elasticsearch.xpack.security.authc.AuthenticationModule;
import org.elasticsearch.xpack.support.clock.Clock;
import org.elasticsearch.xpack.support.clock.SystemClock;
import org.elasticsearch.xpack.watcher.Watcher;

public class XPackPlugin extends Plugin implements ScriptPlugin, ActionPlugin {

    public static final String NAME = "x-pack";

    // inside of YAML settings we still use xpack do not having handle issues with dashes
    public static final String SETTINGS_NAME = "xpack";

    // TODO: clean up this library to not ask for write access to all system properties!
    static {
        // invoke this clinit in unbound with permissions to access all system properties
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }
        try {
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                @Override
                public Void run() {
                    try {
                        Class.forName("com.unboundid.util.Debug");
                    } catch (ClassNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }
            });
            // TODO: fix gradle to add all security resources (plugin metadata) to test classpath
            // of watcher plugin, which depends on it directly. This prevents these plugins
            // from being initialized correctly by the test framework, and means we have to
            // have this leniency.
        } catch (ExceptionInInitializerError bogus) {
            if (bogus.getCause() instanceof SecurityException == false) {
                throw bogus; // some other bug
            }
        }
        // some classes need to have their own clinit blocks
        BodyPartSource.init();
        Account.init();
    }

    protected final Settings settings;
    private final Environment env;
    protected boolean transportClientMode;
    protected final XPackExtensionsService extensionsService;

    protected Licensing licensing;
    protected Security security;
    protected Monitoring monitoring;
    protected Watcher watcher;
    protected Graph graph;
    protected Notification notification;

    public XPackPlugin(Settings settings) throws IOException {
        this.settings = settings;
        this.transportClientMode = transportClientMode(settings);
        this.env = transportClientMode ? null : new Environment(settings);

        this.licensing = new Licensing(settings);
        this.security = new Security(settings, env);
        this.monitoring = new Monitoring(settings);
        this.watcher = new Watcher(settings);
        this.graph = new Graph(settings);
        this.notification = new Notification(settings);
        // Check if the node is a transport client.
        if (transportClientMode == false) {
            this.extensionsService = new XPackExtensionsService(settings, resolveXPackExtensionsFile(env), getExtensions());
        } else {
            this.extensionsService = null;
        }
    }

    // For tests only
    public Collection<Class<? extends XPackExtension>> getExtensions() {
        return Collections.emptyList();
    }

    // overridable by tests
    protected Clock getClock() {
        return SystemClock.INSTANCE;
    }

    @Override
    public Collection<Module> createGuiceModules() {
        ArrayList<Module> modules = new ArrayList<>();
        modules.add(b -> b.bind(Clock.class).toInstance(getClock()));
        modules.addAll(notification.nodeModules());
        modules.addAll(licensing.nodeModules());
        modules.addAll(security.nodeModules());
        modules.addAll(watcher.nodeModules());
        modules.addAll(monitoring.nodeModules());
        modules.addAll(graph.createGuiceModules());

        if (transportClientMode == false) {
            modules.add(new TextTemplateModule());
        }
        return modules;
    }

    @Override
    public Collection<Class<? extends LifecycleComponent>> getGuiceServiceClasses() {
        ArrayList<Class<? extends LifecycleComponent>> services = new ArrayList<>();
        services.addAll(notification.nodeServices());
        services.addAll(licensing.nodeServices());
        services.addAll(security.nodeServices());
        services.addAll(watcher.nodeServices());
        services.addAll(monitoring.nodeServices());
        services.addAll(graph.getGuiceServiceClasses());
        return services;
    }

    @Override
    public Collection<Object> createComponents(Client client, ClusterService clusterService, ThreadPool threadPool,
            ResourceWatcherService resourceWatcherService) {
        List<Object> components = new ArrayList<>();
        if (transportClientMode == false) {
            final InternalClient internalClient = new InternalClient(settings, threadPool, client, security.getCryptoService());
            components.add(internalClient);

            // watcher http stuff
            Map<String, HttpAuthFactory> httpAuthFactories = new HashMap<>();
            httpAuthFactories.put(BasicAuth.TYPE, new BasicAuthFactory(security.getCryptoService()));
            // TODO: add more auth types, or remove this indirection
            HttpAuthRegistry httpAuthRegistry = new HttpAuthRegistry(httpAuthFactories);
            components.add(new HttpRequestTemplate.Parser(httpAuthRegistry));
            components.add(new HttpClient(settings, httpAuthRegistry, env));
        }

        return components;
    }

    @Override
    public Settings additionalSettings() {
        Settings.Builder builder = Settings.builder();
        builder.put(security.additionalSettings());
        builder.put(watcher.additionalSettings());
        builder.put(graph.additionalSettings());
        return builder.build();
    }

    @Override
    public ScriptContext.Plugin getCustomScriptContexts() {
        return ScriptServiceProxy.INSTANCE;
    }

    @Override
    public List<Setting<?>> getSettings() {
        ArrayList<Setting<?>> settings = new ArrayList<>();
        settings.addAll(notification.getSettings());
        settings.addAll(security.getSettings());
        settings.addAll(MonitoringSettings.getSettings());
        settings.addAll(watcher.getSettings());
        settings.addAll(graph.getSettings());
        settings.addAll(licensing.getSettings());
        // we add the `xpack.version` setting to all internal indices
        settings.add(Setting.simpleString("index.xpack.version", Setting.Property.IndexScope));

        // http settings
        settings.add(Setting.simpleString("xpack.http.default_read_timeout", Setting.Property.NodeScope));
        settings.add(Setting.simpleString("xpack.http.default_connection_timeout", Setting.Property.NodeScope));
        settings.add(Setting.groupSetting("xpack.http.ssl.", Setting.Property.NodeScope));
        settings.add(Setting.groupSetting("xpack.http.proxy.", Setting.Property.NodeScope));
        return settings;
    }

    @Override
    public List<String> getSettingsFilter() {
        List<String> filters = new ArrayList<>();
        filters.addAll(notification.getSettingsFilter());
        filters.addAll(security.getSettingsFilter());
        filters.addAll(MonitoringSettings.getSettingsFilter());
        filters.addAll(graph.getSettingsFilter());
        return filters;
    }

    @Override
    public List<ExecutorBuilder<?>> getExecutorBuilders(final Settings settings) {
        return watcher.getExecutorBuilders(settings);
    }

    public void onModule(NetworkModule module) {
        security.onModule(module);
    }

    @Override
    public List<ActionHandler<? extends ActionRequest<?>, ? extends ActionResponse>> getActions() {
        List<ActionHandler<? extends ActionRequest<?>, ? extends ActionResponse>> actions = new ArrayList<>();
        actions.add(new ActionHandler<>(XPackInfoAction.INSTANCE, TransportXPackInfoAction.class));
        actions.add(new ActionHandler<>(XPackUsageAction.INSTANCE, TransportXPackUsageAction.class));
        actions.addAll(licensing.getActions());
        actions.addAll(monitoring.getActions());
        actions.addAll(security.getActions());
        actions.addAll(watcher.getActions());
        actions.addAll(graph.getActions());
        return actions;
    }

    @Override
    public List<Class<? extends ActionFilter>> getActionFilters() {
        List<Class<? extends ActionFilter>> filters = new ArrayList<>();
        filters.addAll(licensing.getActionFilters());
        filters.addAll(monitoring.getActionFilters());
        filters.addAll(security.getActionFilters());
        filters.addAll(watcher.getActionFilters());
        filters.addAll(graph.getActionFilters());
        return filters;
    }

    @Override
    public List<Class<? extends RestHandler>> getRestHandlers() {
        List<Class<? extends RestHandler>> handlers = new ArrayList<>();
        handlers.add(RestXPackInfoAction.class);
        handlers.add(RestXPackUsageAction.class);
        handlers.addAll(licensing.getRestHandlers());
        handlers.addAll(monitoring.getRestHandlers());
        handlers.addAll(security.getRestHandlers());
        handlers.addAll(watcher.getRestHandlers());
        handlers.addAll(graph.getRestHandlers());
        return handlers;
    }

    public void onModule(AuthenticationModule module) {
        if (extensionsService != null) {
            extensionsService.onModule(module);
        }
    }

    public void onIndexModule(IndexModule module) {
        security.onIndexModule(module);
        graph.onIndexModule(module);
    }

    public static void bindFeatureSet(Binder binder, Class<? extends XPackFeatureSet> featureSet) {
        binder.bind(featureSet).asEagerSingleton();
        Multibinder<XPackFeatureSet> featureSetBinder = Multibinder.newSetBinder(binder, XPackFeatureSet.class);
        featureSetBinder.addBinding().to(featureSet);
    }

    public static boolean transportClientMode(Settings settings) {
        return TransportClient.CLIENT_TYPE.equals(settings.get(Client.CLIENT_TYPE_SETTING_S.getKey()));
    }

    public static boolean isTribeNode(Settings settings) {
        return settings.getGroups("tribe", true).isEmpty() == false;
    }
    public static boolean isTribeClientNode(Settings settings) {
        return settings.get("tribe.name") != null;
    }

    public static Path resolveConfigFile(Environment env, String name) {
        return env.configFile().resolve(NAME).resolve(name);
    }

    /**
     * A consistent way to enable disable features using the following setting:
     *
     *          {@code "xpack.<feature>.enabled": true | false}
     *
     *  Also supports the following setting as a fallback (for BWC with 1.x/2.x):
     *
     *          {@code "<feature>.enabled": true | false}
     */
    public static boolean featureEnabled(Settings settings, String featureName, boolean defaultValue) {
        return settings.getAsBoolean(featureEnabledSetting(featureName),
                settings.getAsBoolean(legacyFeatureEnabledSetting(featureName), defaultValue)); // for bwc
    }

    public static String featureEnabledSetting(String featureName) {
        return featureSettingPrefix(featureName) + ".enabled";
    }

    public static String featureSettingPrefix(String featureName) {
        return SETTINGS_NAME + "." + featureName;
    }

    public static String legacyFeatureEnabledSetting(String featureName) {
        return featureName + ".enabled";
    }

    /**
     * A consistent way to register the settings used to enable disable features, supporting the following format:
     *
     *          {@code "xpack.<feature>.enabled": true | false}
     *
     *  Also supports the following setting as a fallback (for BWC with 1.x/2.x):
     *
     *          {@code "<feature>.enabled": true | false}
     */
    public static void addFeatureEnabledSettings(List<Setting<?>> settingsList, String featureName, boolean defaultValue) {
        settingsList.add(Setting.boolSetting(featureEnabledSetting(featureName), defaultValue, Setting.Property.NodeScope));
        settingsList.add(Setting.boolSetting(legacyFeatureEnabledSetting(featureName),
                defaultValue, Setting.Property.NodeScope));
    }

    public static Path resolveXPackExtensionsFile(Environment env) {
        return env.pluginsFile().resolve(XPackPlugin.NAME).resolve("extensions");
    }
}
