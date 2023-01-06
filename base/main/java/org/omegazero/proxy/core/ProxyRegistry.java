/*
 * Copyright (C) 2023 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.omegazero.common.logging.Logger;
import org.omegazero.common.util.function.SpecificThrowingConsumer;
import org.omegazero.net.client.NetClientManager;
import org.omegazero.net.server.NetServer;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.config.ProxyConfiguration;
import org.omegazero.proxy.http.HTTPEngine;
import org.omegazero.proxy.http.HTTPErrdoc;
import org.omegazero.proxy.net.UpstreamServer;

/**
 * Used for configuring and registering proxy settings or extensions.
 *
 * @since 3.9.1
 */
public final class ProxyRegistry {

	private static final Logger logger = Logger.create();

	private static final String DEFAULT_ERRDOC_LOCATION = "/org/omegazero/proxy/resources/errdoc.html";

	private final List<NetServer> serverInstances = new ArrayList<>();
	private final Map<String, NetClientManager> clientManagers = new HashMap<>();

	private final List<Function<SocketConnection, Class<? extends HTTPEngine>>> httpEngineSelectors = new ArrayList<>();

	private final Map<String, HTTPErrdoc> errdocs = new HashMap<>();
	private HTTPErrdoc errdocDefault;

	ProxyRegistry(){
	}


	/**
	 * Registers a new {@link NetServer} instance of the given type.
	 * <p>
	 * A new instance of the given type is created and registered using {@link #registerServerInstance(NetServer)}. This requires that the given type has a constructor with no
	 * parameters. If the type requires additional arguments in the constructor, use <code>Proxy.registerServerInstance(NetServer)</code> directly instead.
	 *
	 * @param c The server class
	 * @throws IllegalStateException If this method is called after the <i>INIT</i> phase
	 */
	public void registerServerInstance(Class<? extends NetServer> c){
		try{
			NetServer server = c.newInstance();
			this.registerServerInstance(server);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException("Failed to register server instance of type '" + c.getName() + "'", e);
		}
	}

	/**
	 * Registers a new {@link NetServer} instance. The instance is added to the list of registered instances and will be initialized at the end of the main initialization
	 * phase, meaning server instances must be registered during the {@link ProxyEvents#PREINIT} or {@link ProxyEvents#INIT} event.
	 *
	 * @param server The server instance
	 * @throws IllegalStateException If this method is called after the <i>INIT</i> phase
	 */
	public void registerServerInstance(NetServer server){
		Proxy.getInstance().requireStateMax(State.INIT);
		this.serverInstances.add(server);
		logger.info("Added server instance ", server.getClass().getName());
	}

	/**
	 * Registers a new {@link NetClientManager} for outgoing connections.
	 *
	 * @param id The identifier for the client manager
	 * @param mgr The client manager
	 */
	public void registerClientManager(String id, NetClientManager mgr){
		this.clientManagers.put(id, mgr);
	}

	/**
	 * Registers a new {@link HTTPEngine} selector.
	 * <p>
	 * For incoming connections, an appropriate <code>HTTPEngine</code> must be selected. For this, every registered selector is called until one returns a non-<code>null</code>
	 * value, which will be the <code>HTTPEngine</code> used for the connection. If all selectors return <code>null</code>, an <code>UnsupportedOperationException</code> is thrown
	 * and the connection will be closed.
	 *
	 * @param selector The selector
	 */
	public void addHTTPEngineSelector(Function<SocketConnection, Class<? extends HTTPEngine>> selector){
		this.httpEngineSelectors.add(selector);
	}

	/**
	 * Sets an error document for the given MIME type (<i>Content-Type</i> header in HTTP).
	 * <p>
	 * The error document is returned by {@link #getErrdoc(String)} when given the MIME type. The {@link HTTPEngine} implementation may choose any way to determine the
	 * appropriate error document type for a request, but usually does so using the <i>Accept</i> HTTP request header.
	 *
	 * @param type The content type to set this error document for
	 * @param errdoc The error document
	 */
	public void setErrdoc(String type, HTTPErrdoc errdoc){
		errdoc.setServername(Proxy.getInstance().getInstanceName());
		this.errdocs.put(type, errdoc);
	}

	/**
	 * Returns the error document set for the given <b>type</b>. If no error document for the given type was set using {@link #setErrdoc(String, HTTPErrdoc)}, the default
	 * error document is returned ({@link #getDefaultErrdoc()}).
	 *
	 * @param type The MIME type of the error document
	 * @return An error document of the given MIME type or the default error document if none was found
	 * @see #getErrdocForAccept(String)
	 */
	public HTTPErrdoc getErrdoc(String type) {
		HTTPErrdoc errdoc = this.errdocs.get(type);
		if(errdoc == null){
			errdoc = this.errdocDefault;
		}
		return errdoc;
	}

	/**
	 * Returns the default error document. This is guaranteed to be non-<code>null</code>.
	 *
	 * @return The default error document
	 */
	public HTTPErrdoc getDefaultErrdoc() {
		return this.errdocDefault;
	}

	/**
	 * Parses the given value of an <i>Accept</i> HTTP header and returns the {@code HTTPErrdoc} for the first content type in the header for which an error document is found. If no
	 * overlap is found, or the header does not exist (the given value is <code>null</code>), the default error document is returned.
	 *
	 * @param accept The value of an <i>Accept</i> HTTP header
	 * @return A suitable <code>HTTPErrdoc</code>, or the default errdoc if none was found
	 * @see #getErrdoc(String)
	 * @see #getDefaultErrdoc()
	 */
	public HTTPErrdoc getErrdocForAccept(String accept) {
		HTTPErrdoc errdoc = null;
		if(accept != null){
			String[] acceptParts = accept.split(",");
			for(String ap : acceptParts){
				int pe = ap.indexOf(';');
				if(pe >= 0)
					ap = ap.substring(0, pe);
				errdoc = this.errdocs.get(ap.trim());
				if(errdoc != null)
					break;
			}
		}
		if(errdoc == null)
			errdoc = this.getDefaultErrdoc();
		return errdoc;
	}


	void forEachServerInstance(SpecificThrowingConsumer<IOException, ? super NetServer> callback) throws IOException {
		for(NetServer server : this.serverInstances){
			callback.accept(server);
		}
	}

	void forEachClientManager(SpecificThrowingConsumer<IOException, ? super NetClientManager> callback) throws IOException {
		for(NetClientManager cm : this.clientManagers.values()){
			callback.accept(cm);
		}
	}

	NetClientManager getClientManager(String cmid){
		return this.clientManagers.get(cmid);
	}


	Class<? extends HTTPEngine> selectHTTPEngine(SocketConnection conn){
		Class<? extends HTTPEngine> c = null;
		for(Function<SocketConnection, Class<? extends HTTPEngine>> sel : this.httpEngineSelectors){
			c = sel.apply(conn);
			if(c != null)
				break;
		}
		return c;
	}


	void loadErrdocs(ProxyConfiguration config) throws IOException {
		if(!config.getErrdocFiles().isEmpty()){
			for(String t : config.getErrdocFiles().keySet()){
				String file = config.getErrdocFiles().get(t);
				logger.debug("Loading errdoc '", file, "' (", t, ")");
				HTTPErrdoc errdoc = HTTPErrdoc.fromString(new String(Files.readAllBytes(Paths.get(file))), t);
				this.setErrdoc(t, errdoc);
				if(this.errdocDefault == null)
					this.errdocDefault = errdoc;
			}
			if(!this.errdocs.containsKey("text/html")){
				logger.debug("No errdoc of type 'text/html', loading default: ", DEFAULT_ERRDOC_LOCATION);
				this.loadDefaultErrdoc();
			}
		}else{
			logger.debug("No errdocs configured, loading default: ", DEFAULT_ERRDOC_LOCATION);
			this.loadDefaultErrdoc();
		}
	}

	void loadDefaultErrdoc() throws IOException {
		java.io.InputStream defErrdocStream = ProxyRegistry.class.getResourceAsStream(DEFAULT_ERRDOC_LOCATION);
		if(defErrdocStream == null)
			throw new IOException("Default errdoc (" + DEFAULT_ERRDOC_LOCATION + ") not found");
		byte[] defErrdocData = new byte[defErrdocStream.available()];
		defErrdocStream.read(defErrdocData);
		HTTPErrdoc defErrdoc = HTTPErrdoc.fromString(new String(defErrdocData));
		this.setErrdoc(defErrdoc.getMimeType(), defErrdoc);
		this.errdocDefault = defErrdoc;
	}
}
