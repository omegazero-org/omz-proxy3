/*
 * Copyright (C) 2023 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2;

import org.omegazero.common.logging.Logger;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.config.HTTPEngineConfig;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.http.AbstractHTTPEngine;

object HTTP2 {

	final val logger = Logger.create();

	final val ALPN_NAME = "h2";
	final val VERSION_NAME = "HTTP/2";
}

class HTTP2(downstreamConnection: SocketConnection, proxy: Proxy, config: HTTPEngineConfig)
		extends AbstractHTTPEngine(downstreamConnection, proxy, config, new ProxyHTTP2Server(downstreamConnection, config)) {

	this.httpServer.asInstanceOf[ProxyHTTP2Server].onError = this.respondError(_, _, _);

	override def getRequestLogger(): Logger = HTTP2.logger;
	override def getHTTPVersionName(): String = HTTP2.VERSION_NAME;
	override def isDownstreamConnectionSecure(): Boolean = this.downstreamConnection.isInstanceOf[org.omegazero.net.socket.TLSConnection];
}
