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
import java.util.Set;

import javax.net.ssl.SSLContext;

import org.omegazero.common.eventbus.Event;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.net.common.NetworkApplicationBuilder;
import org.omegazero.net.server.NetServer;
import org.omegazero.net.util.TrustManagerUtil;
import org.omegazero.proxy.config.ProxyConfiguration;
import org.omegazero.proxy.util.FeatureSet;

class Defaults {

	private static final Logger logger = LoggerUtil.createLogger();

	protected static void featureInit(Proxy proxy, FeatureSet featureSet) {
		logger.debug("Defaults init with feature set: ", featureSet);

		ProxyConfiguration config = proxy.getConfig();
		List<java.net.InetAddress> bindAddresses = config.getBindAddresses();
		if(featureSet.containsFeature("tcp.server.plain") && config.getPortsPlain().size() > 0){
			NetServer server = NetworkApplicationBuilder.newServer("nio")
					.bindAddresses(bindAddresses)
					.ports(config.getPortsPlain())
					.connectionBacklog(config.getBacklog())
					.connectionIdleTimeout(proxy.getConnectionIdleTimeout())
					.workerCreator(proxy::getSessionWorkerProvider)
					.build();
			proxy.getRegistry().registerServerInstance(server);
		}
		if(featureSet.containsFeature("tcp.server.tls") && config.getPortsTls().size() > 0){
			List<Object> alpnNames = proxy.dispatchEventRes(new Event("proxy_registerALPNOption", false, new Class<?>[0], String.class, true)).getReturnValues();
			logger.debug("Registered TLS ALPN options: ", alpnNames);
			NetServer tlsServer = NetworkApplicationBuilder.newServer("nio")
					.bindAddresses(bindAddresses)
					.ports(config.getPortsTls())
					.connectionBacklog(config.getBacklog())
					.connectionIdleTimeout(proxy.getConnectionIdleTimeout())
					.workerCreator(proxy::getSessionWorkerProvider)
					.sslContext(proxy.getSslContext())
					.applicationLayerProtocols(alpnNames.toArray(new String[alpnNames.size()]))
					.build();
			proxy.getRegistry().registerServerInstance(tlsServer);
		}

		if(featureSet.containsFeature("tcp.client.plain")){
			proxy.getRegistry().registerClientManager("tcp.client.plain", NetworkApplicationBuilder.newClientManager("nio").build());
		}
		if(featureSet.containsFeature("tcp.client.tls")){
			try{
				SSLContext clientSslContext = SSLContext.getInstance("TLS");
				clientSslContext.init(null, TrustManagerUtil.getTrustManagersWithAdditionalCertificateFiles(config.getTrustedCertificates()), null);
				proxy.getRegistry().registerClientManager("tcp.client.tls", NetworkApplicationBuilder.newClientManager("nio").sslContext(clientSslContext).build());
			}catch(GeneralSecurityException | IOException e){
				throw new RuntimeException("Error while loading trusted certificates", e);
			}
		}
	}
}
