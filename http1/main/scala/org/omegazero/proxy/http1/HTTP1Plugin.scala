/*
 * Copyright (C) 2023 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http1;

import org.omegazero.common.config.ConfigurationOption;
import org.omegazero.common.eventbus.{EventBusSubscriber, SubscribeEvent};
import org.omegazero.net.socket.TLSConnection;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.util.ProxyUtil;

@EventBusSubscriber
class HTTP1Plugin {

	@ConfigurationOption
	private var enable: Boolean = true;


	@SubscribeEvent
	def proxy_requiredFeatureSet() : String = if this.enable then ProxyUtil.clientImplNamespace + ".*," + ProxyUtil.serverImplNamespace + ".*" else null;

	@SubscribeEvent(priority = SubscribeEvent.Priority.LOW)
	def proxy_registerALPNOption() : String = if this.enable then HTTP1.ALPN_NAME else null;

	@SubscribeEvent(priority = SubscribeEvent.Priority.LOW)
	def onInit() : Unit = {
		if(!this.enable)
			return;
		Proxy.getInstance().getRegistry().addHTTPEngineSelector((connection) => {
			if(connection.isInstanceOf[TLSConnection]){
				var alpnProtocolName = connection.asInstanceOf[TLSConnection].getApplicationProtocol();
				if(alpnProtocolName == null || alpnProtocolName == HTTP1.ALPN_NAME) classOf[HTTP1] else null;
			}else
				classOf[HTTP1];
		});
		Proxy.getInstance().getRegistry().registerHTTPClientImplementation(HTTP1.VERSION_NAME, new ProxyHTTP1Client(_, _, _, _), HTTP1.ALPN_NAME);
	}
}
