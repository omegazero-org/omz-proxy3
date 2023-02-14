/*
 * Copyright (C) 2023 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2;

import org.omegazero.common.config.ConfigurationOption;
import org.omegazero.common.eventbus.{EventBusSubscriber, SubscribeEvent};
import org.omegazero.net.socket.TLSConnection;
import org.omegazero.proxy.core.Proxy;

@EventBusSubscriber
class HTTP2Plugin {

	@ConfigurationOption
	private var enable: Boolean = true;


	@SubscribeEvent
	def proxy_requiredFeatureSet() : String = if this.enable then "tcp.*" else null;

	@SubscribeEvent
	def proxy_registerALPNOption() : String = if this.enable then HTTP2.ALPN_NAME else null;

	@SubscribeEvent
	def onInit() : Unit = {
		if(!this.enable)
			return;
		Proxy.getInstance().getRegistry().addHTTPEngineSelector((connection) => {
			if(connection.isInstanceOf[TLSConnection] && connection.asInstanceOf[TLSConnection].getApplicationProtocol() == HTTP2.ALPN_NAME)
				classOf[HTTP2];
			else
				null;
		});
		Proxy.getInstance().getRegistry().registerHTTPClientImplementation(HTTP2.VERSION_NAME, new ProxyHTTP2Client(_, _, _, _), HTTP2.ALPN_NAME);
	}
}
