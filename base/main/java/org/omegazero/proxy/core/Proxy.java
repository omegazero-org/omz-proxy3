/*
 * Copyright (C) 2021 omegazero.org
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Covered Software is provided under this License on an "as is" basis, without warranty of any kind,
 * either expressed, implied, or statutory, including, without limitation, warranties that the Covered Software
 * is free of defects, merchantable, fit for a particular purpose or non-infringing.
 * The entire risk as to the quality and performance of the Covered Software is with You.
 */
package org.omegazero.proxy.core;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;

import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.event.DeferringTaskQueueExecutor;
import org.omegazero.common.event.DelegatingTaskQueueExecutor;
import org.omegazero.common.event.TaskQueueExecutor;
import org.omegazero.common.event.Tasks;
import org.omegazero.common.eventbus.Event;
import org.omegazero.common.eventbus.EventBus;
import org.omegazero.common.eventbus.EventResult;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.common.plugins.Plugin;
import org.omegazero.common.plugins.PluginManager;
import org.omegazero.common.runtime.Application;
import org.omegazero.common.runtime.ApplicationWrapper;
import org.omegazero.common.util.Args;
import org.omegazero.net.client.NetClientManager;
import org.omegazero.net.client.params.ConnectionParameters;
import org.omegazero.net.common.NetworkApplication;
import org.omegazero.net.server.NetServer;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.config.HTTPEngineConfig;
import org.omegazero.proxy.config.ProxyConfiguration;
import org.omegazero.proxy.http.HTTPEngine;
import org.omegazero.proxy.http.HTTPErrdoc;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.FeatureSet;

/**
 * The main class of <i>omz-proxy</i>.
 */
public final class Proxy implements Application {

	private static final Logger logger = LoggerUtil.createLogger();

	/**
	 * The version string of <i>omz-proxy</i>.
	 * 
	 * @see #getVersion()
	 */
	public static final String VERSION = "$BUILDVERSION";


	private static Proxy instance;


	private State state = State.NEW;

	private Path configFile;
	private long configLastModified = 0;
	private ProxyConfiguration config;
	private String instanceType = "proxy";
	private String instanceVersion = Proxy.VERSION;
	private String instanceNameAppendage;
	private String instanceName;

	private PluginManager pluginManager;
	private EventBus proxyEventBus;

	private ProxyKeyManager keyManager;
	private SSLContext sslContext;
	private Object tlsDataReloadInterval;

	private DelegatingTaskQueueExecutor serverWorker = new DelegatingTaskQueueExecutor(new DeferringTaskQueueExecutor());
	private ApplicationWorkerProvider serverWorkerProvider;

	private UpstreamServer defaultUpstreamServer;

	private ProxyRegistry registry = new ProxyRegistry();

	private int nAppCount = 0;

	public Proxy() {
		if(instance != null)
			throw new IllegalStateException("An instance of " + this.getClass().getName() + " already exists");
		instance = this;
	}


	@Override
	public void start(Args args) throws Exception {
		Thread.currentThread().setName("InitializationThread");
		this.init(args);
	}

	public synchronized void init(Args args) throws IOException {
		this.requireStateMax(State.NEW);

		logger.info("omz-proxy (java) version ", VERSION, ", omz-net-lib version ", org.omegazero.net.common.NetCommon.getVersion(), ", omz-http-lib version ",
				org.omegazero.http.HTTPLib.getVersion());

		this.updateState(State.PREINIT);
		String configCmdData = args.getValue("config");
		if(configCmdData != null){
			this.loadConfiguration(configCmdData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}else{
			String configFile = args.getValueOrDefault("configFile", "config.json");
			logger.info("Using configuration '", configFile, "'");
			Path filePath = Paths.get(configFile).toAbsolutePath();
			this.loadConfiguration(filePath);
			this.configFile = filePath;
			if(args.getBooleanOrDefault("configFileReload", false)){
				java.io.File configF = filePath.toFile();
				this.configLastModified = configF.lastModified();
				Tasks.I.interval((a) -> {
					long lm = configF.lastModified();
					if(lm > Proxy.this.configLastModified){
						Proxy.this.configLastModified = lm;
						try{
							logger.info("Configuration file was modified, reloading");
							Proxy.this.reloadConfiguration();
						}catch(Exception e){
							logger.error("Error while reloading configuration: ", e);
						}
					}
				}, 5000).daemon();
			}
		}
		this.config.validateConfig();

		this.proxyEventBus = new EventBus();

		this.loadPlugins(args.getValueOrDefault("pluginDir", "plugins").split("::"), args.getBooleanOrDefault("dirPlugins", false));

		this.dispatchEvent(ProxyEvents.PREINIT);

		this.registry.loadErrdocs(this.config);

		this.updateState(State.INIT);
		logger.info("Loading SSL context; ", this.config.getTlsAuthData().size(), " server names configured");
		this.loadSSLContext();

		int wtc = this.config.getWorkerThreadCount();
		logger.info("Setting up worker threads (configured max: ", wtc, ")");
		this.serverWorker.setErrorHandler((e) -> {
			logger.fatal("Error in server worker: ", e);
			Proxy.this.shutdown();
		});
		TaskQueueExecutor actualWorker = TaskQueueExecutor.fromSequential().name("Worker").workerThreads(wtc).build();
		((DeferringTaskQueueExecutor) this.serverWorker.getDelegate()).replaceFor(this.serverWorker, actualWorker);

		this.serverWorkerProvider = new ApplicationWorkerProvider();

		FeatureSet featureSet = new FeatureSet();
		java.util.List<Object> featureSetRes = this.dispatchEventRes(new Event("proxy_requiredFeatureSet", false, new Class<?>[0], String.class, true)).getReturnValues();
		for(Object o : featureSetRes){
			if(o != null)
				featureSet.addList((String) o);
		}
		this.dispatchEvent(new Event("proxy_featureInit", new Class<?>[] { FeatureSet.class }), featureSet);
		Defaults.featureInit(this, featureSet);
		this.dispatchEvent(ProxyEvents.INIT);

		this.updateState(State.STARTING);
		this.registry.forEachServerInstance(this::initServerInstance);
		this.registry.forEachClientManager(this::initApplication);
		if(this.nAppCount == 0)
			throw new IllegalStateException("No network applications were started");

		this.updateState(State.RUNNING);
		logger.info("Initialization complete (", this.nAppCount, " network applications started)");
	}


	@Override
	public synchronized void close() throws IOException {
		if(!ApplicationWrapper.isShuttingDown())
			throw new IllegalStateException("Not shutting down");
		if(this.state.value() >= State.STOPPING.value())
			return;
		logger.info("Closing");
		this.updateState(State.STOPPING);

		if(this.proxyEventBus != null)
			this.dispatchEvent(ProxyEvents.SHUTDOWN);

		this.registry.forEachServerInstance(NetServer::close);
		this.registry.forEachClientManager(NetClientManager::close);
		if(this.serverWorker != null)
			this.serverWorker.exit();

		this.updateState(State.STOPPED);

		Proxy.instance = null;
	}


	public void shutdown() {
		this.requireStateMin(State.STARTING);
		this.serverWorker.queue((args) -> {
			ApplicationWrapper.shutdown();
		}, 10);
	}


	private void loadConfiguration(Path filePath) throws IOException {
		this.loadConfiguration(Files.readAllBytes(filePath));
	}

	private void loadConfiguration(byte[] data) throws IOException {
		ProxyConfiguration config = new ProxyConfiguration(data);
		config.load();
		this.config = config;

		if(this.tlsDataReloadInterval != null)
			Tasks.I.clear(this.tlsDataReloadInterval);
		if(this.config.getTlsAuthReloadInterval() > 0){
			this.tlsDataReloadInterval = Tasks.I.interval((args) -> {
				try{
					Proxy.this.config.reloadTLSAuthData();
					Proxy.this.keyManager.tlsDataReload();
				}catch(Exception e){
					logger.error("Error while reloading TLS auth data: ", e);
				}
			}, this.config.getTlsAuthReloadInterval() * 1000).daemon();
		}

		this.defaultUpstreamServer = this.config.createDefaultUpstreamServerInstance();
	}


	private void loadPlugins(String[] pluginDirs, boolean dirPlugins) throws IOException {
		this.pluginManager = new PluginManager();
		int pluginFlags = dirPlugins ? PluginManager.ALLOW_DIRS : PluginManager.RECURSIVE;
		for(String p : pluginDirs){
			logger.info("Loading plugins from '", p, "'");
			this.pluginManager.loadFromPath(p, pluginFlags);
		}

		String[] pluginNames = new String[this.pluginManager.pluginCount()];
		int pluginIndex = 0;
		this.pushPluginConfig();
		for(Plugin p : this.pluginManager){
			try{
				logger.trace("Registering plugin class at event bus: ", p.getMainClassType().getName());
				String eventsList = p.getAdditionalOption("events");
				String[] events;
				if(eventsList != null && eventsList.length() > 0)
					events = eventsList.split(",");
				else
					events = new String[0];
				for(String event : events){
					if(!(ProxyEvents.isValidEventName(event) || event.contains("_") /* special event names */))
						throw new IllegalArgumentException("Invalid event '" + event + "' listed in plugin configuration file");
				}
				this.proxyEventBus.register(p.getMainClassInstance(), events);
			}catch(Exception e){
				logger.error("Error while registering plugin '" + p.getName() + "': ", e);
			}

			if(p.getVersion() != null)
				pluginNames[pluginIndex++] = p.getId() + "/" + p.getVersion();
			else
				pluginNames[pluginIndex++] = p.getId();
		}
		logger.info("Loaded ", pluginNames.length, " plugins: ", java.util.Arrays.toString(pluginNames));
	}

	private void pushPluginConfig() {
		for(Plugin p : this.pluginManager){
			logger.trace("Applying plugin configuration for '", p.getName(), "'");
			ConfigObject pconfig = this.config.getPluginConfigFor(p.getId());
			try{
				p.initPluginConfig(pconfig);
			}catch(Exception e){
				throw new RuntimeException("Error reloading configuration of plugin '" + p.getName() + "': " + e, e);
			}
			try{
				// backward compatibility
				java.lang.reflect.Method configMethod = p.getMainClassType().getDeclaredMethod("configurationReload", ConfigObject.class);
				if(!configMethod.isAnnotationPresent(org.omegazero.common.plugins.ExtendedPluginConfiguration.class)){
					logger.warn("Plugin '" + p.getName() + "': Use of deprecated configurationReload(ConfigObject) configuration API;"
							+ " use @ExtendedPluginConfiguration and @ConfigurationOption annotations instead");
					configMethod.setAccessible(true);
					configMethod.invoke(p.getMainClassInstance(), pconfig);
				}
			}catch(java.lang.reflect.InvocationTargetException e){
				throw new RuntimeException("Error in config reload method of plugin '" + p.getName() + "'", e);
			}catch(NoSuchMethodException e){
				// ignore
			}catch(ReflectiveOperationException e){
				logger.warn("Error while attempting to call config reload method of plugin '" + p.getName() + "': ", e.toString());
			}
		}
	}

	private void loadSSLContext() {
		try{
			this.keyManager = new ProxyKeyManager(this);
			this.sslContext = SSLContext.getInstance("TLS");
			this.sslContext.init(new KeyManager[] { this.keyManager }, null, new SecureRandom());
		}catch(GeneralSecurityException e){
			throw new RuntimeException("SSL context initialization failed", e);
		}
	}


	/**
	 * Registers a new {@link NetServer} instance of the given type.
	 * <p>
	 * A new instance of the given type is created and registered using {@link Proxy#registerServerInstance(NetServer)}. This requires that the given type has a constructor with no
	 * parameters. If the type requires additional arguments in the constructor, use <code>Proxy.registerServerInstance(NetServer)</code> directly instead.
	 * 
	 * @param c
	 * @deprecated Since 3.9.1, use <code>{@link getRegistry()}.{@link ProxyRegistry#registerServerInstance(Class) registerServerInstance(c)}</code> instead
	 */
	@Deprecated
	public void registerServerInstance(Class<? extends NetServer> c) {
		this.registry.registerServerInstance(c);
	}

	/**
	 * Registers a new {@link NetServer} instance. The instance is added to the list of registered instances and will be initialized at the beginning of the main initialization
	 * phase, meaning server instances must be registered during the {@link ProxyEvents#PREINIT} event.
	 * 
	 * @param server
	 * @deprecated Since 3.9.1, use <code>{@link getRegistry()}.{@link ProxyRegistry#registerServerInstance(NetServer) registerServerInstance(server)}</code> instead
	 */
	@Deprecated
	public void registerServerInstance(NetServer server) {
		this.registry.registerServerInstance(server);
	}

	/**
	 * Registers a new {@link HTTPEngine} selector.
	 * <p>
	 * For incoming connections, an appropriate <code>HTTPEngine</code> must be selected. For this, every registered selector is called until one returns a non-<code>null</code>
	 * value, which will be the <code>HTTPEngine</code> used for the connection. If all selectors return <code>null</code>, an <code>UnsupportedOperationException</code> is thrown
	 * and the connection will be closed.
	 * 
	 * @param selector
	 * @deprecated Since 3.9.1, use <code>{@link getRegistry()}.{@link ProxyRegistry#addHTTPEngineSelector(Function, Class) addHTTPEngineSelector(selector)}</code> instead
	 */
	@Deprecated
	public void addHTTPEngineSelector(Function<SocketConnection, Class<? extends HTTPEngine>> selector) {
		this.registry.addHTTPEngineSelector(selector);
	}

	/**
	 * Registers a new {@link NetClientManager} for outgoing connections.
	 * 
	 * @param mgr The client manager
	 * @deprecated Since 3.7.1, use {@link #registerClientManager(String, NetClientManager)}. This method uses the full class name for the <b>id</b> parameter.
	 */
	@Deprecated
	public void registerClientManager(NetClientManager mgr) {
		this.registerClientManager(mgr.getClass().getName(), mgr);
	}

	/**
	 * Registers a new {@link NetClientManager} for outgoing connections.
	 * 
	 * @param id The identifier for the client manager
	 * @param mgr The client manager
	 * @since 3.7.1
	 * @deprecated Since 3.9.1, use <code>{@link getRegistry()}.{@link ProxyRegistry#registerClientManager(String, NetClientManager) registerClientManager(id, mgr)}</code> instead
	 */
	@Deprecated
	public void registerClientManager(String id, NetClientManager mgr) {
		this.registry.registerClientManager(id, mgr);
	}


	private void initServerInstance(NetServer server) {
		server.setConnectionCallback(this::onNewConnection);
		this.initApplication(server);
	}

	private void initApplication(NetworkApplication app) {
		try{
			app.init();
			ApplicationThread thread = new ApplicationThread(app);
			thread.start();
			this.nAppCount++;
		}catch(IOException e){
			throw new RuntimeException("Failed to initialize application of type '" + app.getClass().getName() + "'", e);
		}
	}


	private HTTPEngine createHTTPEngineInstance(Class<? extends HTTPEngine> engineType, SocketConnection downstreamConnection) {
		HTTPEngine engine = null;
		try{
			java.lang.reflect.Constructor<? extends HTTPEngine> cons = engineType.getConstructor(SocketConnection.class, Proxy.class, HTTPEngineConfig.class);
			engine = cons.newInstance(downstreamConnection, this, this.config.getEngineConfigFor(engineType));
		}catch(ReflectiveOperationException e){
			throw new RuntimeException("Failed to create instance of '" + engineType.getName() + "'", e);
		}
		return engine;
	}

	private void onNewConnection(SocketConnection conn) {
		final String msgToProxy;
		if(logger.debug()){
			msgToProxy = this.debugStringForConnection(conn, null);
			logger.debug(msgToProxy, " Connected");
		}else
			msgToProxy = null;

		final AtomicReference<HTTPEngine> engineRef = new AtomicReference<>();

		conn.on("close", () -> {
			if(logger.debug())
				logger.debug(msgToProxy, " Disconnected");
			HTTPEngine engine = engineRef.get();
			if(engine != null)
				engine.close();
			this.dispatchEvent(ProxyEvents.DOWNSTREAM_CONNECTION_CLOSED, conn);
		});

		this.dispatchEvent(ProxyEvents.DOWNSTREAM_CONNECTION, conn);
		if(!conn.isConnected()) // connection might have been closed by an event handler
			return;

		Class<? extends HTTPEngine> engineType = this.registry.selectHTTPEngine(conn);
		if(engineType == null){
			logger.warn("Could not find HTTPEngine for socket of type " + conn.getClass().getName());
			conn.destroy();
			return;
		}

		HTTPEngine engine = this.createHTTPEngineInstance(engineType, conn);
		engineRef.set(engine);
		if(logger.debug())
			logger.debug(msgToProxy, " HTTPEngine type: ", engineType.getName());

		conn.on("data", (org.omegazero.common.event.runnable.GenericRunnable.A1<byte[]>) engine::processData);
	}


	/**
	 * Reloads the configuration from the configuration file and pushes the new configuration data to plugins.
	 * <p>
	 * If no configuration file was used, this method does nothing.
	 * 
	 * @throws IOException
	 */
	public void reloadConfiguration() throws IOException {
		if(this.configFile == null)
			return;
		this.loadConfiguration(this.configFile);
		this.keyManager.tlsDataReload();
		this.pushPluginConfig();
	}


	/**
	 * Creates a new connection instance for an outgoing proxy connection.
	 * 
	 * @param type The {@link NetClientManager} type to use for this connection
	 * @param parameters Parameters for this connection
	 * @return The new connection instance
	 * @throws IOException
	 * @throws IllegalArgumentException If an <code>NetClientManager</code> type was given for which there is no active instance
	 * @see NetClientManager#connection(ConnectionParameters)
	 * @deprecated Since 3.7.1, use {@link #connection(String, ConnectionParameters)}. This method uses the full class name of the given type as the <b>cmid</b> parameter.
	 */
	@Deprecated
	public SocketConnection connection(Class<? extends NetClientManager> type, ConnectionParameters parameters) throws IOException {
		return this.connection(type.getName(), parameters, null);
	}

	/**
	 * Creates a new connection instance for an outgoing proxy connection.
	 * 
	 * @param cmid The client manager ID passed in {@link #registerClientManager(String, NetClientManager)}
	 * @param parameters Parameters for this connection
	 * @param downstreamConnection The downstream connection, if available. Used to set the worker instance used for the new connection
	 * @return The new connection instance
	 * @throws IOException
	 * @throws IllegalArgumentException If <b>cmid</b> is invalid
	 * @since 3.7.1
	 * @see NetClientManager#connection(ConnectionParameters)
	 */
	public SocketConnection connection(String cmid, ConnectionParameters parameters, SocketConnection downstreamConnection) throws IOException {
		this.requireState(State.RUNNING);
		NetClientManager mgr = this.registry.getClientManager(cmid);
		if(mgr == null)
			throw new IllegalArgumentException("Invalid client manager id '" + cmid + "'");
		SocketConnection conn = mgr.connection(parameters);
		if(conn instanceof org.omegazero.net.socket.AbstractSocketConnection && downstreamConnection instanceof org.omegazero.net.socket.AbstractSocketConnection)
			((org.omegazero.net.socket.AbstractSocketConnection) conn).setWorker(((org.omegazero.net.socket.AbstractSocketConnection) downstreamConnection).getWorker());
		return conn;
	}


	/**
	 * Delegates the given event to this proxy's {@link EventBus}.
	 * 
	 * @param event The event to dispatch using this proxy's event bus
	 * @param args The arguments to pass the event handlers
	 * @return The number of handlers executed
	 * @see EventBus#dispatchEvent(Event, Object...)
	 */
	public int dispatchEvent(Event event, Object... args) {
		if(logger.debug())
			logger.trace("Proxy EventBus event <fast>: ", event.getEventSignature());
		return ProxyEvents.runEvent(this.proxyEventBus, event, args);
	}

	/**
	 * Delegates the given event to this proxy's {@link EventBus}.
	 * 
	 * @param event The event to dispatch using this proxy's event bus
	 * @param args The arguments to pass the event handlers
	 * @return An {@link EventResult} object containing information about this event execution
	 * @see EventBus#dispatchEventRes(Event, Object...)
	 */
	public EventResult dispatchEventRes(Event event, Object... args) {
		if(logger.debug())
			logger.trace("Proxy EventBus event <res>: ", event.getEventSignature());
		return ProxyEvents.runEventRes(this.proxyEventBus, event, args);
	}

	/**
	 * Delegates the given event to this proxy's {@link EventBus} and returns a <code>boolean</code> value returned by the event handlers.
	 * <p>
	 * If {@link Event#isIncludeAllReturns()} is <code>false</code>, the return value of the first event handler that returns a non-<code>null</code> value is returned. If all
	 * event handlers return <code>null</code> or there are none, <b>def</b> will be returned.<br>
	 * Otherwise, if <code>includeAllReturns</code> is <code>true</code>, all event handlers will be executed and the value of <b>def</b> is returned if all event handlers return
	 * either <code>null</code> or the same value as <b>def</b>. Otherwise, the return value of the first event handler is returned that does not match the <b>def</b> value.
	 * 
	 * @param event The event to dispatch using this proxy's event bus
	 * @param def The value to return if all event handlers return null or there are none
	 * @param args The arguments to pass the event handlers
	 * @return The <code>boolean</code> value returned by the first event handler that returns a non-<code>null</code> value, or <b>def</b>
	 */
	public boolean dispatchBooleanEvent(Event event, boolean def, Object... args) {
		EventResult res = this.dispatchEventRes(event, args);
		if(event.isIncludeAllReturns()){
			for(Object ret : res.getReturnValues()){
				boolean b = (boolean) ret;
				if(b != def)
					return b;
			}
			return def;
		}else{
			if(res.getReturnValue() instanceof Boolean)
				return (boolean) res.getReturnValue();
			else
				return def;
		}
	}


	public String debugStringForConnection(SocketConnection incoming, SocketConnection outgoing) {
		return "[" + incoming.getRemoteAddress() + "]->[" + this.instanceType + ":" + ((java.net.InetSocketAddress) incoming.getLocalAddress()).getPort() + "]"
				+ (outgoing != null ? ("->[" + outgoing.getRemoteAddress() + "]") : "");
	}


	public State getState() {
		return this.state;
	}

	public boolean isRunning() {
		return this.state == State.STARTING || this.state == State.RUNNING;
	}


	public ProxyConfiguration getConfig() {
		return this.config;
	}

	public String getInstanceType() {
		return this.instanceType;
	}

	public void setInstanceType(String instanceType) {
		this.requireStateMax(State.PREINIT);
		this.instanceType = Objects.requireNonNull(instanceType);
		this.instanceName = null;
	}

	public String getInstanceVersion() {
		return this.instanceVersion;
	}

	public void setInstanceVersion(String instanceVersion) {
		this.requireStateMax(State.PREINIT);
		this.instanceVersion = Objects.requireNonNull(instanceVersion);
		this.instanceName = null;
	}

	public String getInstanceNameAppendage() {
		return this.instanceNameAppendage;
	}

	public void setInstanceNameAppendage(String instanceNameAppendage) {
		this.requireStateMax(State.PREINIT);
		this.instanceNameAppendage = instanceNameAppendage;
		this.instanceName = null;
	}

	public String getInstanceName() {
		if(this.instanceName == null)
			this.instanceName = "omz-" + this.instanceType + "/" + this.instanceVersion + " (" + System.getProperty("os.name")
					+ (this.instanceNameAppendage != null ? (") " + this.instanceNameAppendage) : ")");
		return this.instanceName;
	}

	/**
	 * Checks whether a plugins with the given <b>id</b> is loaded.
	 *
	 * @param id The id of the plugin to search for
	 * @return <code>true</code> if a plugin with the given id exists
	 */
	public boolean isPluginLoaded(String id) {
		for(Plugin p : this.pluginManager){
			if(p.getId().equals(id))
				return true;
		}
		return false;
	}

	/**
	 * Returns the {@link SSLContext} for this proxy
	 *
	 * @return The configured SSL context for this proxy
	 */
	public SSLContext getSslContext() {
		return this.sslContext;
	}

	/**
	 * Returns the {@link ApplicationWorkerProvider} for use by plugins for long-running tasks.
	 * 
	 * @return The {@code ApplicationWorkerProvider}
	 */
	public ApplicationWorkerProvider getServerWorkerProvider() {
		return this.serverWorkerProvider;
	}

	/**
	 * Returns a {@link SessionWorkerProvider} for the given client connection. This method may be passed as a method reference to a {@code NetworkApplicationBuilder} as the worker
	 * creator.
	 * 
	 * @param client The client connection
	 * @return The {@code SessionWorkerProvider}
	 * @since 3.7.1
	 */
	public SessionWorkerProvider getSessionWorkerProvider(SocketConnection client) {
		return new SessionWorkerProvider();
	}

	/**
	 * Calls <code>{@link getRegistry()}.{@link ProxyRegistry#getErrdoc(String) getErrdoc(type)}</code>.
	 * 
	 * @param type The MIME type of the error document
	 * @return A {@code HTTPErrdoc} instance of the given MIME type or the default error document if none was found
	 */
	public HTTPErrdoc getErrdoc(String type) {
		return this.getRegistry().getErrdoc(type);
	}

	/**
	 * Calls <code>{@link getRegistry()}.{@link ProxyRegistry#getDefaultErrdoc() getDefaultErrdoc()}</code>.
	 * 
	 * @return The default error document
	 */
	public HTTPErrdoc getDefaultErrdoc() {
		return this.getRegistry().getDefaultErrdoc();
	}

	/**
	 * Calls <code>{@link getRegistry()}.{@link ProxyRegistry#getErrdocForAccept(String) getErrdocForAccept(accept)}</code>.
	 *
	 * @param accept The value of an <i>Accept</i> HTTP header
	 * @return A suitable <code>HTTPErrdoc</code>, or the default errdoc if none was found
	 * @since 3.3.1
	 * @see #getErrdoc(String)
	 * @see #getDefaultErrdoc()
	 */
	public HTTPErrdoc getErrdocForAccept(String accept){
		return this.getRegistry().getErrdocForAccept(accept);
	}

	/**
	 * Sets an error document for the given MIME type.
	 * 
	 * @param type The content type to set this error document for
	 * @param errdoc The <code>HTTPErrdoc</code> instance
	 * @deprecated Since 3.9.1, use <code>{@link getRegistry()}.{@link ProxyRegistry#setErrdoc(String, HTTPErrdoc) setErrdoc(type, errdoc)}</code> instead
	 */
	@Deprecated
	public void setErrdoc(String type, HTTPErrdoc errdoc){
		this.getRegistry().setErrdoc(type, errdoc);
	}

	/**
	 * Returns the amount of time in seconds a connection with no traffic should persist before it is closed.
	 * 
	 * @return The time in seconds
	 */
	public int getConnectionIdleTimeout() {
		return this.config.getConnectionIdleTimeout();
	}

	/**
	 * Returns the default upstream server configured in the configuration file. May be <code>null</code>.
	 * 
	 * @return The default upstream server
	 */
	public UpstreamServer getDefaultUpstreamServer() {
		return this.defaultUpstreamServer;
	}

	/**
	 * Selects an upstream server based on the given hostname and path.
	 * <p>
	 * This method uses the EventBus event {@code selectUpstreamServer}. If all event handlers return <code>null</code>, the default upstream server, which may also
	 * be <code>null</code>, is selected.
	 * 
	 * @param host The hostname to choose a server for
	 * @param path The request path
	 * @return The {@link UpstreamServer} instance for the given <b>host</b>, or <code>null</code> if no appropriate server was found
	 * @deprecated Since 3.9.1, this method always returns the value returned by {@link #getDefaultUpstreamServer()}. The {@code selectUpstreamServer} event was replaced by
	 *		 {@code onHTTPRequestSelectServer}
	 */
	@Deprecated
	public UpstreamServer getUpstreamServer(String host, String path) {
		return this.getDefaultUpstreamServer();
	}

	/**
	 * Returns the {@link ProxyRegistry} for this proxy.
	 *
	 * @return The registry
	 * @since 3.9.1
	 */
	public ProxyRegistry getRegistry(){
		return this.registry;
	}


	/**
	 * Returns the currently active instance of <code>Proxy</code>, or <code>null</code> if there is none.
	 * 
	 * @return The instance
	 */
	public static Proxy getInstance() {
		return instance;
	}

	/**
	 * Returns the version string of <i>omz-proxy</i>.
	 * 
	 * @return The version string
	 * @since 3.7.1
	 */
	public static String getVersion() {
		return VERSION;
	}

	/**
	 * Returns the {@code TaskQueueExecutor} used to execute asynchronous tasks.
	 * <p>
	 * Plugins and other external components SHOULD NOT use this method to queue asynchronous tasks, but use Proxy.{@link #getInstance()}.{@link #getServerWorkerProvider()} instead.
	 *
	 * @return The {@code TaskQueueExecutor}
	 * @since 3.10.3
	 */
	public static DelegatingTaskQueueExecutor getServerWorker() {
		return getInstance().serverWorker;
	}


	private void updateState(State newState) {
		if(newState.value() < this.state.value())
			throw new IllegalArgumentException(newState + " is lower than " + this.state);
		this.state = newState;
	}

	void requireState(State state) {
		if(this.state.value() != state.value())
			throw new IllegalStateException("Requires state " + state + " but proxy is in state " + this.state);
	}

	void requireStateMin(State state) {
		if(this.state.value() < state.value())
			throw new IllegalStateException("Requires state " + state + " or after but proxy is in state " + this.state);
	}

	void requireStateMax(State state) {
		if(this.state.value() > state.value())
			throw new IllegalStateException("Requires state " + state + " or before but proxy is already in state " + this.state);
	}


	/**
	 * A class used to queue tasks.
	 * 
	 * @see Proxy#getServerWorkerProvider()
	 */
	public class ApplicationWorkerProvider implements Consumer<Runnable> {

		private ApplicationWorkerProvider() {
		}

		@Override
		public void accept(Runnable t) {
			Proxy.this.serverWorker.queue((args) -> {
				t.run();
			}, 0);
		}
	}

	/**
	 * A class used for running the connection callbacks in a client session. Each {@code SessionWorkerProvider} is used for a single client connection and all upstream
	 * connections, and wraps a single {@link TaskQueueExecutor.Handle}.
	 * 
	 * @since 3.7.1
	 * @see Proxy#getSessionWorkerProvider(SocketConnection)
	 */
	public class SessionWorkerProvider implements Consumer<Runnable> {

		private final TaskQueueExecutor.Handle handle;

		private SessionWorkerProvider() {
			Proxy.this.requireStateMin(State.STARTING);
			this.handle = ((TaskQueueExecutor) Proxy.this.serverWorker.getDelegate()).newHandle();
		}

		@Override
		public void accept(Runnable t) {
			this.handle.queue((args) -> {
				t.run();
			}, 0);
		}
	}

	private class ApplicationThread extends Thread {

		private final NetworkApplication application;

		private final String name;

		public ApplicationThread(NetworkApplication application) {
			this.application = application;

			this.name = application.getClass().getSimpleName();
			super.setName(this.name + "Thread");
		}

		@Override
		public void run() {
			logger.debug("Application thread for '" + this.name + "' started");
			try{
				this.application.start();
				if(Proxy.this.isRunning())
					throw new IllegalStateException("Application loop of '" + this.name + "' returned while proxy is still running");
			}catch(Throwable e){
				logger.fatal("Error in '" + this.name + "' application loop: ", e);
				Proxy.this.shutdown();
			}
		}
	}
}
