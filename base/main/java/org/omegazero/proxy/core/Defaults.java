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
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.function.Consumer;

import org.omegazero.common.eventbus.Event;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.net.client.PlainTCPClientManager;
import org.omegazero.net.client.TLSClientManager;
import org.omegazero.net.server.PlainTCPServer;
import org.omegazero.net.server.TLSServer;
import org.omegazero.net.socket.impl.PlainConnection;
import org.omegazero.net.socket.impl.TLSConnection;
import org.omegazero.net.util.TrustManagerUtil;
import org.omegazero.proxy.config.ProxyConfiguration;
import org.omegazero.proxy.http.HTTPEngine;

@SuppressWarnings("unchecked")
class Defaults {

	private static final Logger logger = LoggerUtil.createLogger();

	private static final Class<? extends HTTPEngine> http1Impl; // TODO: move http1 impl to plugin


	protected static void registerProxyDefaults(Proxy proxy) {
		ProxyConfiguration config = proxy.getConfig();
		Consumer<Runnable> serverTaskHandler = null; // change to proxy worker when synchronization issues are resolved (is that ever going to happen?)
		if(config.getPortsPlain().size() > 0)
			proxy.registerServerInstance(
					new PlainTCPServer(config.getBindAddresses(), config.getPortsPlain(), config.getBacklog(), serverTaskHandler, proxy.getConnectionIdleTimeout()));
		if(config.getPortsTls().size() > 0){
			List<Object> alpnNames = proxy.dispatchEventRes(new Event("_proxyRegisterALPNOption", false, new Class<?>[0], String.class, true)).getReturnValues();
			alpnNames.add("http/1.1");
			logger.debug("Registered TLS ALPN options: ", alpnNames);
			TLSServer tlsServer = new TLSServer(config.getBindAddresses(), config.getPortsTls(), config.getBacklog(), serverTaskHandler, proxy.getConnectionIdleTimeout(),
					proxy.getSslContext());
			tlsServer.setSupportedApplicationLayerProtocols(alpnNames.toArray(new String[alpnNames.size()]));
			proxy.registerServerInstance(tlsServer);
		}

		proxy.registerClientManager(new PlainTCPClientManager(serverTaskHandler));
		try{
			proxy.registerClientManager(
					new TLSClientManager(serverTaskHandler, TrustManagerUtil.getTrustManagersWithAdditionalCertificateFiles(config.getTrustedCertificates())));
		}catch(GeneralSecurityException | IOException e){
			throw new RuntimeException("Error while loading trusted certificates", e);
		}

		proxy.addHTTPEngineSelector((connection) -> {
			if(connection instanceof PlainConnection)
				return http1Impl;
			else if(connection instanceof TLSConnection){
				String alpnProtocolName = ((TLSConnection) connection).getAlpnProtocol();
				if(alpnProtocolName == null || alpnProtocolName.equals("http/1.1"))
					return http1Impl;
			}
			return null;
		});
	}


	static{
		try{
			http1Impl = (Class<? extends HTTPEngine>) Class.forName("org.omegazero.proxy.http1.HTTP1");
		}catch(ClassNotFoundException e){
			throw new RuntimeException(e);
		}
	}
}
