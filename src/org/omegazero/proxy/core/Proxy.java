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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;

import org.omegazero.common.event.EventQueueExecutor;
import org.omegazero.common.event.Tasks;
import org.omegazero.common.eventbus.Event;
import org.omegazero.common.eventbus.EventBus;
import org.omegazero.common.eventbus.EventResult;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.common.plugins.Plugin;
import org.omegazero.common.plugins.PluginManager;
import org.omegazero.common.util.Args;
import org.omegazero.net.client.NetClientManager;
import org.omegazero.net.client.params.ConnectionParameters;
import org.omegazero.net.common.NetworkApplication;
import org.omegazero.net.server.NetServer;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.config.ConfigObject;
import org.omegazero.proxy.config.ProxyConfiguration;
import org.omegazero.proxy.http.HTTPEngine;
import org.omegazero.proxy.http.HTTPErrdoc;
import org.omegazero.proxy.net.UpstreamServer;

public final class Proxy {

	private static final Logger logger = LoggerUtil.createLogger();

	public static final String VERSION = "3.1.1";

	private static final String DEFAULT_ERRDOC_LOCATION = "/org/omegazero/proxy/resources/errdoc.html";


	private static Proxy instance;


	private State state = State.NEW;

	private ProxyConfiguration config;
	private String instanceType = "proxy";
	private String instanceVersion = Proxy.VERSION;
	private String instanceNameAppendage;
	private String instanceName;

	private PluginManager pluginManager;
	private EventBus proxyEventBus;

	private ProxyKeyManager keyManager;
	private SSLContext sslContext;

	private EventQueueExecutor serverWorker;
	private ApplicationWorkerProvider serverWorkerProvider;

	private final List<Function<SocketConnection, Class<? extends HTTPEngine>>> httpEngineSelectors = new ArrayList<>();

	private final Map<String, HTTPErrdoc> errdocs = new HashMap<>();
	private HTTPErrdoc errdocDefault;

	private UpstreamServer defaultUpstreamServer;

	private List<NetServer> serverInstances = new ArrayList<>();
	private Map<Class<? extends NetClientManager>, NetClientManager> clientManagers = new HashMap<>();

	public Proxy() {
		if(instance != null)
			throw new IllegalStateException("An instance of " + this.getClass().getName() + " already exists");
		instance = this;
	}


	public synchronized void init(Args args) throws IOException {
		this.requireStateMax(State.NEW);

		this.updateState(State.PREINIT);
		String configCmdData = args.getValue("config");
		if(configCmdData != null){
			this.loadConfiguration(configCmdData.getBytes());
		}else{
			String configFile = args.getValueOrDefault("configFile", "config.json");
			logger.info("Loading configuration '", configFile, "'");
			this.loadConfiguration(configFile);
		}
		this.config.validateConfig();

		this.proxyEventBus = new EventBus();

		this.loadPlugins(args.getValueOrDefault("pluginDir", "plugins").split("::"), args.getBooleanOrDefault("dirPlugins", false));

		this.dispatchEvent(ProxyEvents.PREINIT);

		this.updateState(State.INIT);
		logger.info("Loading SSL context; ", this.config.getTlsAuthData().size(), " server names configured");
		this.loadSSLContext();

		this.loadErrdocs();

		this.serverWorker = new EventQueueExecutor(false, "Worker");
		this.serverWorker.setErrorHandler((e) -> {
			logger.fatal("Error in server worker: ", e);
			Proxy.this.shutdown();
		});

		this.serverWorkerProvider = new ApplicationWorkerProvider();

		this.registerDefaults();
		this.dispatchEvent(ProxyEvents.INIT);

		this.updateState(State.STARTING);
		for(NetServer server : this.serverInstances){
			this.initServerInstance(server);
		}
		for(NetClientManager mgr : this.clientManagers.values()){
			this.initApplication(mgr);
		}

		this.updateState(State.RUNNING);
	}

	public void shutdown() {
		this.requireStateMin(State.STARTING);
		this.serverWorker.queue((args) -> {
			ProxyMain.shutdown();
		}, 10);
	}


	private void loadConfiguration(String file) throws IOException {
		this.loadConfiguration(Files.readAllBytes(Paths.get(file)));
	}

	private void loadConfiguration(byte[] data) throws IOException {
		this.config = new ProxyConfiguration(data);
		this.config.load();

		if(this.config.getTlsAuthReloadInterval() > 0){
			Tasks.interval((args) -> {
				try{
					Proxy.this.config.reloadTLSAuthData();
					Proxy.this.keyManager.tlsDataReload();
				}catch(Exception e){
					logger.error("Error while reloading TLS auth data: ", e);
				}
			}, this.config.getTlsAuthReloadInterval() * 1000).daemon();
		}

		if(this.config.getUpstreamServerAddress() != null)
			this.defaultUpstreamServer = new UpstreamServer(InetAddress.getByName(this.config.getUpstreamServerAddress()), this.config.getUpstreamServerPortPlain(),
					this.config.getUpstreamServerPortTLS());
	}


	private void loadPlugins(String[] pluginDirs, boolean dirPlugins) throws IOException {
		logger.debug("Loading plugins");
		this.pluginManager = new PluginManager();
		int pluginFlags = dirPlugins ? PluginManager.ALLOW_DIRS : PluginManager.RECURSIVE;
		for(String p : pluginDirs){
			Path pluginDir = Paths.get(p);
			if(Files.exists(pluginDir))
				this.pluginManager.loadFromDirectory(pluginDir, pluginFlags);
			else
				logger.warn("Plugin directory '", pluginDir, "' does not exist");
		}

		String[] pluginNames = new String[this.pluginManager.pluginCount()];
		int pluginIndex = 0;
		for(Plugin p : this.pluginManager){
			try{
				java.lang.reflect.Method configMethod = p.getMainClassType().getDeclaredMethod("configurationReload", ConfigObject.class);
				configMethod.setAccessible(true);
				configMethod.invoke(p.getMainClassInstance(), Proxy.this.config.getPluginConfigFor(p.getId()));
			}catch(java.lang.reflect.InvocationTargetException e){
				throw new RuntimeException("Error in config reload method of plugin '" + p.getName() + "'", e);
			}catch(ReflectiveOperationException e){
				logger.warn("Error while attempting to call config reload method of plugin '" + p.getName() + "': ", e.toString());
			}

			try{
				logger.debug("Registering plugin class with event bus: ", p.getMainClassType().getName());
				String eventsList = p.getAdditionalOption("events");
				String[] events;
				if(eventsList != null)
					events = eventsList.split(",");
				else
					events = new String[0];
				Proxy.this.proxyEventBus.register(p.getMainClassInstance(), events);
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

	private void loadSSLContext() {
		try{
			this.keyManager = new ProxyKeyManager(this);
			this.sslContext = SSLContext.getInstance("TLS");
			this.sslContext.init(new KeyManager[] { this.keyManager }, null, new SecureRandom());
		}catch(GeneralSecurityException e){
			throw new RuntimeException("SSL context initialization failed", e);
		}
	}

	private void registerDefaults() {
		Defaults.registerProxyDefaults(this);
	}

	private void loadErrdocs() throws IOException {
		if(!this.config.getErrdocFiles().isEmpty()){
			logger.info("Loading error documents");
			for(String t : this.config.getErrdocFiles().keySet()){
				String file = this.config.getErrdocFiles().get(t);
				logger.debug("Loading errdoc '", file, "' (", t, ")");
				HTTPErrdoc errdoc = HTTPErrdoc.fromString(new String(Files.readAllBytes(Paths.get(file))), t);
				errdoc.setServername(this.getInstanceName());
				this.errdocs.put(t, errdoc);
				if(this.errdocDefault == null)
					this.errdocDefault = errdoc;
			}
			if(!this.errdocs.containsKey("text/html")){
				logger.debug("No errdoc of type 'text/html', loading default: ", Proxy.DEFAULT_ERRDOC_LOCATION);
				this.loadDefaultErrdoc();
			}
		}else{
			logger.debug("No errdocs configured, loading default: ", Proxy.DEFAULT_ERRDOC_LOCATION);
			this.loadDefaultErrdoc();
		}
	}

	private void loadDefaultErrdoc() throws IOException {
		java.io.InputStream defErrdocStream = Proxy.class.getResourceAsStream(Proxy.DEFAULT_ERRDOC_LOCATION);
		if(defErrdocStream == null)
			throw new IOException("Default errdoc (" + Proxy.DEFAULT_ERRDOC_LOCATION + ") not found");
		byte[] defErrdocData = new byte[defErrdocStream.available()];
		defErrdocStream.read(defErrdocData);
		HTTPErrdoc defErrdoc = HTTPErrdoc.fromString(new String(defErrdocData));
		defErrdoc.setServername(this.getInstanceName());
		this.errdocDefault = defErrdoc;
		this.errdocs.put(defErrdoc.getMimeType(), defErrdoc);
	}


	/**
	 * Registers a new {@link NetServer} instance of the given type.<br>
	 * <br>
	 * A new instance of the given type is created and registered using {@link Proxy#registerServerInstance(NetServer)}. This requires that the given type has a constructor
	 * with no parameters. If the type requires additional arguments in the constructor, use <code>Proxy.registerServerInstance(NetServer)</code> directly instead.
	 * 
	 * @param c
	 */
	public void registerServerInstance(Class<? extends NetServer> c) {
		try{
			NetServer server = c.newInstance();
			this.registerServerInstance(server);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException("Failed to register server instance of type '" + c.getName() + "'", e);
		}
	}

	/**
	 * Registers a new {@link NetServer} instance. The instance is added to the list of registered instances and will be initialized at the beginning of the main
	 * initialization phase, meaning server instances must be registered during the {@link ProxyEvents#PREINIT} event.
	 * 
	 * @param server
	 */
	public void registerServerInstance(NetServer server) {
		this.serverInstances.add(server);
		logger.info("Added server instance ", server.getClass().getName());
	}

	private void initServerInstance(NetServer server) {
		server.setConnectionCallback(this::onNewConnection);
		this.initApplication(server);
	}

	/**
	 * Registers a new {@link HTTPEngine} selector.<br>
	 * <br>
	 * For incoming connections, an appropriate <code>HTTPEngine</code> must be selected. For this, every registered selector is called until one returns a
	 * non-<code>null</code> value, which will be the <code>HTTPEngine</code> used for the connection. If all selectors return <code>null</code>, an
	 * <code>UnsupportedOperationException</code> is thrown and the connection will be closed.
	 * 
	 * @param selector
	 */
	public void addHTTPEngineSelector(Function<SocketConnection, Class<? extends HTTPEngine>> selector) {
		this.httpEngineSelectors.add(selector);
	}

	/**
	 * Registers a new {@link NetClientManager} for outgoing connections.
	 * 
	 * @param mgr
	 */
	public void registerClientManager(NetClientManager mgr) {
		this.clientManagers.put(mgr.getClass(), mgr);
	}

	private void initApplication(NetworkApplication app) {
		try{
			app.init();
			ApplicationThread thread = new ApplicationThread(app);
			thread.start();
		}catch(IOException e){
			throw new RuntimeException("Failed to initialize application of type '" + app.getClass().getName() + "'", e);
		}
	}


	protected synchronized void close() throws IOException {
		if(this.state.value() >= State.STOPPING.value())
			return;
		logger.info("Closing");
		this.updateState(State.STOPPING);

		if(this.proxyEventBus != null)
			this.dispatchEvent(ProxyEvents.SHUTDOWN);

		for(NetServer server : this.serverInstances){
			server.close();
		}
		for(NetClientManager mgr : this.clientManagers.values()){
			mgr.close();
		}
		if(this.serverWorker != null)
			this.serverWorker.exit();

		this.updateState(State.STOPPED);

		Proxy.instance = null;
	}


	private Class<? extends HTTPEngine> selectHTTPEngine(SocketConnection conn) {
		Class<? extends HTTPEngine> c = null;
		for(Function<SocketConnection, Class<? extends HTTPEngine>> sel : this.httpEngineSelectors){
			c = sel.apply(conn);
			if(c != null)
				break;
		}
		if(c == null)
			throw new UnsupportedOperationException("Did not find HTTPEngine for socket of type " + conn.getClass().getName());
		return c;
	}

	private HTTPEngine createHTTPEngineInstance(Class<? extends HTTPEngine> engineType, SocketConnection downstreamConnection) {
		HTTPEngine engine = null;
		try{
			java.lang.reflect.Constructor<? extends HTTPEngine> cons = engineType.getConstructor(SocketConnection.class, Proxy.class);
			engine = cons.newInstance(downstreamConnection, this);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException("Failed to create instance of '" + engineType.getName() + "'", e);
		}
		return engine;
	}

	private void onNewConnection(SocketConnection conn) {
		String msgToProxy = this.debugStringForConnection(conn, null);
		logger.debug(msgToProxy, " Connected");

		final AtomicReference<HTTPEngine> engineRef = new AtomicReference<>();

		conn.setOnClose(() -> {
			logger.debug(msgToProxy, " Disconnected");
			HTTPEngine engine = engineRef.get();
			if(engine != null)
				engine.close();
			Proxy.this.dispatchEvent(ProxyEvents.DOWNSTREAM_CONNECTION_CLOSED, conn);
		});

		this.dispatchEvent(ProxyEvents.DOWNSTREAM_CONNECTION, conn);

		HTTPEngine engine = this.createHTTPEngineInstance(this.selectHTTPEngine(conn), conn);
		engineRef.set(engine);
		logger.debug(msgToProxy, " HTTPEngine type: ", engine.getClass().getName());

		conn.setOnData((data) -> {
			try{
				engineRef.get().processData(data);
			}catch(Exception e){
				throw new RuntimeException("Error while processing data", e);
			}
		});
	}


	/**
	 * Creates a new connection instance for an outgoing proxy connection.
	 * 
	 * @param type       The {@link NetClientManager} type to use for this connection
	 * @param parameters Parameters for this connection
	 * @return The new connection instance
	 * @throws IOException
	 * @throws IllegalArgumentException If an <code>NetClientManager</code> type was given for which there is no active instance
	 * @see NetClientManager#connection(ConnectionParameters)
	 */
	public SocketConnection connection(Class<? extends NetClientManager> type, ConnectionParameters parameters) throws IOException {
		this.requireState(State.RUNNING);
		NetClientManager mgr = this.clientManagers.get(type);
		if(mgr == null)
			throw new IllegalArgumentException("Cannot connect with type " + type.getName());
		return mgr.connection(parameters);
	}


	/**
	 * Delegates the given event to this proxy's {@link EventBus}.
	 * 
	 * @param event The event to dispatch using this proxy's event bus
	 * @param args  The arguments to pass the event handlers
	 * @return The number of handlers executed
	 * @see EventBus#dispatchEvent(Event, Object...)
	 */
	public int dispatchEvent(Event event, Object... args) {
		logger.trace("Proxy EventBus event <fast>: '", event.getMethodName(), "' ", event.getEventSignature());
		return ProxyEvents.runEvent(this.proxyEventBus, event, args);
	}

	/**
	 * Delegates the given event to this proxy's {@link EventBus}.
	 * 
	 * @param event The event to dispatch using this proxy's event bus
	 * @param args  The arguments to pass the event handlers
	 * @return An {@link EventResult} object containing information about this event execution
	 * @see EventBus#dispatchEventRes(Event, Object...)
	 */
	public EventResult dispatchEventRes(Event event, Object... args) {
		logger.trace("Proxy EventBus event <res>: '", event.getMethodName(), "' ", event.getEventSignature());
		return ProxyEvents.runEventRes(this.proxyEventBus, event, args);
	}

	/**
	 * Delegates the given event to this proxy's {@link EventBus} and returns a <code>boolean</code> value returned by the event handlers.<br>
	 * <br>
	 * If {@link Event#isIncludeAllReturns()} is <code>false</code>, the return value of the first event handler that returns a non-<code>null</code> value is returned. If all
	 * event handlers return <code>null</code> or there are none, <b>def</b> will be returned.<br>
	 * Otherwise, if <code>includeAllReturns</code> is <code>true</code>, all event handlers will be executed and the value of <b>def</b> is returned if all event handlers
	 * return either <code>null</code> or the same value as <b>def</b>. Otherwise, the return value of the first event handler is returned that does not match the <b>def</b>
	 * value.
	 * 
	 * @param event The event to dispatch using this proxy's event bus
	 * @param def   The value to return if all event handlers return null or there are none
	 * @param args  The arguments to pass the event handlers
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
	 * 
	 * @return The configured SSL context for this proxy
	 */
	public SSLContext getSslContext() {
		return this.sslContext;
	}

	public ApplicationWorkerProvider getServerWorkerProvider() {
		return this.serverWorkerProvider;
	}

	/**
	 * Returns the error document set for the given <b>type</b> using {@link Proxy#setErrdoc(String, HTTPErrdoc)}. If no error document for the given type was set, the default
	 * error document is returned ({@link Proxy#getDefaultErrdoc()}).
	 * 
	 * @param type The MIME type of the error document
	 * @return A {@link HTTPErrdoc} instance of the given MIME type or the default error document if none was found
	 */
	public HTTPErrdoc getErrdoc(String type) {
		HTTPErrdoc errdoc = this.errdocs.get(type);
		if(errdoc == null){
			errdoc = this.errdocDefault;
		}
		return errdoc;
	}

	public HTTPErrdoc getDefaultErrdoc() {
		return this.errdocDefault;
	}

	/**
	 * Sets an error document for the given MIME type (<b>Content-Type</b> header in HTTP).<br>
	 * <br>
	 * The error document is returned by {@link Proxy#getErrdoc(String)} when given the MIME type. The {@link HTTPEngine} implementation may choose any way to determine the
	 * appropriate error document type for a request, but usually does so using the <b>Accept</b> HTTP request header.
	 * 
	 * @param type   The content type to set this error document for
	 * @param errdoc The <code>HTTPErrdoc</code> instance
	 */
	public void setErrdoc(String type, HTTPErrdoc errdoc) {
		this.errdocs.put(Objects.requireNonNull(type), Objects.requireNonNull(errdoc));
	}

	/**
	 * 
	 * @return The amount of time in milliseconds a connection with no traffic should persist before it is closed
	 */
	public int getConnectionIdleTimeout() {
		return this.config.getConnectionIdleTimeout() * 1000;
	}

	/**
	 * 
	 * @return The default upstream server configured in the configuration file. May be <code>null</code>
	 */
	public UpstreamServer getDefaultUpstreamServer() {
		return this.defaultUpstreamServer;
	}

	/**
	 * Selects an upstream server based on the given hostname and path.<br>
	 * <br>
	 * This method uses the EventBus event {@link ProxyEvents#SELECT_UPSTREAM_SERVER}. If all event handlers return <code>null</code>, the default upstream server, which may
	 * also be <code>null</code>, is selected.
	 * 
	 * @param host The hostname to choose a server for
	 * @param path
	 * @return The {@link UpstreamServer} instance for the given <b>host</b>, or <code>null</code> if no appropriate server was found
	 */
	public UpstreamServer getUpstreamServer(String host, String path) {
		EventResult res = this.dispatchEventRes(ProxyEvents.SELECT_UPSTREAM_SERVER, host, path);
		UpstreamServer serv = (UpstreamServer) res.getReturnValue();
		if(serv == null)
			serv = this.getDefaultUpstreamServer();
		return serv;
	}

	/**
	 * 
	 * @return The maximum time in milliseconds to wait until a connection to an upstream server is established before the connection attempt should be cancelled and an error
	 *         be reported
	 */
	public int getUpstreamConnectionTimeout() {
		return this.config.getUpstreamConnectionTimeout() * 1000;
	}

	/**
	 * 
	 * @return <code>true</code> if this proxy was configured to add headers to proxied HTTP messages
	 */
	public boolean enableHeaders() {
		return this.config.isEnableHeaders();
	}


	/**
	 * 
	 * @return The currently active instance of <code>Proxy</code>, or <code>null</code> if there is none
	 */
	public static Proxy getInstance() {
		return instance;
	}


	private void updateState(State newState) {
		if(newState.value() < this.state.value())
			throw new IllegalArgumentException(newState + " is lower than " + this.state);
		this.state = newState;
	}

	private void requireState(State state) {
		if(this.state.value() != state.value())
			throw new IllegalStateException("Requires state " + state + " but proxy is in state " + this.state);
	}

	private void requireStateMin(State state) {
		if(this.state.value() < state.value())
			throw new IllegalStateException("Requires state " + state + " or after but proxy is in state " + this.state);
	}

	private void requireStateMax(State state) {
		if(this.state.value() > state.value())
			throw new IllegalStateException("Requires state " + state + " or before but proxy is already in state " + this.state);
	}


	private class ApplicationWorkerProvider implements Consumer<Runnable> {

		@Override
		public void accept(Runnable t) {
			Proxy.this.serverWorker.queue((args) -> {
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
