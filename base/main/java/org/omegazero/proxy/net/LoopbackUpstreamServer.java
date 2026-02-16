/*
 * Copyright (C) 2026 Wilton Arthur Poth
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.net;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import org.omegazero.common.logging.Logger;
import org.omegazero.net.client.NetClientManager;
import org.omegazero.net.common.NetworkApplicationBuilder;
import org.omegazero.net.util.LoopbackServer;
import org.omegazero.proxy.core.Proxy;

/**
 * Virtual upstream server to locally handle requests, based on {@link LoopbackServer}.
 *
 * After creation, {@link #init()} must be called to create and register the {@link LoopbackServer} and client manager instances.
 * Then, {@link #getServer()} returns the server instance where a connection handler should be added to start serving requests.
 *
 * @since 3.11.1
 */
public class LoopbackUpstreamServer extends UpstreamServer implements Closeable {

	private static final long serialVersionUID = 1L;

	private static final Logger logger = Logger.create();

	private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger(1);

	private LoopbackServer server;

	/**
	 * Creates an {@code LoopbackUpstreamServer} instance.
	 */
	public LoopbackUpstreamServer(){
		this(null);
	}

	/**
	 * Creates an {@code LoopbackUpstreamServer} instance.
	 *
	 * @param protocols The list of protocol names the server supports. If not given or {@code null}, the {@linkplain UpstreamServer#PROTOCOLS_DEFAULT default set} is used
	 */
	public LoopbackUpstreamServer(Collection<String> protocols){
		// address and port need to be valid because they probably get passed through ProxyUtil.connectUpstreamTCP() -> InetSocketAddress()
		super(UpstreamServer.LOCALHOST_IPV4, -1, null, 1, -1, protocols, "loopback-inst-" + INSTANCE_COUNTER.getAndIncrement() + ".client");
	}

	private LoopbackUpstreamServer(LoopbackUpstreamServer src, int number){
		super(UpstreamServer.LOCALHOST_IPV4, -1, null, number, -1, src.getSupportedProcotols(), src.getClientImplOverride());
	}

	/**
	 * Initializes a {@link LoopbackServer} and corresponding client manager, and registers the client manager under a unique name in the proxy registry.
	 * When this {@code LoopbackUpstreamServer} instance is used to serve a request, this client manager will be used to create the virtual loopback connection.
	 * <p>
	 * This method <b>must</b> be called in the proxy <code>onPostInit</code> event or later.
	 *
	 * @throws IllegalStateException If this method was called already
	 */
	public void init(){
		if(this.server != null)
			throw new IllegalStateException("Already initialized");
		this.server = (LoopbackServer) NetworkApplicationBuilder.newServer("loopback").workerCreator(Proxy.getInstance()::getSessionWorkerProvider).build();
		NetClientManager clientmgr = NetworkApplicationBuilder.newClientManager("loopback").set("server", this.server).workerCreator(Proxy.getInstance()::getSessionWorkerProvider).build();
		Proxy.getInstance().getRegistry().registerClientManager(this.getClientImplOverride() + ".plain", clientmgr);
	}

	@Override
	public void close() throws IOException {
		this.server.close();
	}

	/**
	 * Creates a new virtual {@code LoopbackUpstreamServer} with the same underlying client manager.
	 * <p>
	 * Such an instance can be used to distinguish connections made to the server returned by {@link #getServer()}.
	 * The {@code number} parameter of this method will be the destination port number of virtual connections to the server,
	 * i.e. the port number in the address returned by the {@code getLocalAddress()} method of the received {@code SocketConnection} instance.
	 *
	 * @param number The virtual port number
	 * @return The new instance
	 * @see #getNumber()
	 */
	public LoopbackUpstreamServer newVirtualInstance(int number){
		return new LoopbackUpstreamServer(this, number);
	}

	/**
	 * Returns the virtual port number of this {@code LoopbackUpstreamServer}.
	 * <p>
	 * For derived instances, this is the number given to {@link newVirtualInstance}. The initial {@code LoopbackUpstreamServer} created using the constructor always has number 1.
	 * <p>
	 * This method returns the same value as {@link getPlainPort()}.
	 *
	 * @return The virtual port number
	 */
	public int getNumber(){
		return super.getPlainPort();
	}


	/**
	 * Returns the {@link LoopbackServer} instance that receives the internal virtual connections.
	 * Use {@link LoopbackServer#setConnectionCallback} to add a connection handler and start serving requests.
	 *
	 * @return The server instance
	 */
	public LoopbackServer getServer(){
		return this.server;
	}


	@Override
	public String toString() {
		return "LoopbackUpstreamServer{" + this.getClientImplOverride() + "}";
	}
}
