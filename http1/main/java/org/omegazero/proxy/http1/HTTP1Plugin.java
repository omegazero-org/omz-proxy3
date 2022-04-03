/*
 * Copyright (C) 2022 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http1;

import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.net.socket.impl.PlainConnection;
import org.omegazero.net.socket.impl.TLSConnection;
import org.omegazero.proxy.core.Proxy;

@EventBusSubscriber
public class HTTP1Plugin {


	private boolean enable;

	public synchronized void configurationReload(ConfigObject config) {
		this.enable = config.optBoolean("enable", true);
	}


	@SubscribeEvent
	public String proxy_requiredFeatureSet() {
		return this.enable ? "tcp.*" : null;
	}

	@SubscribeEvent(priority = SubscribeEvent.Priority.LOW)
	public String proxy_registerALPNOption() {
		return this.enable ? HTTP1.HTTP1_ALPN_NAME : null;
	}

	@SubscribeEvent(priority = SubscribeEvent.Priority.HIGH)
	public void onInit() {
		if(!this.enable)
			return;
		Proxy.getInstance().addHTTPEngineSelector((connection) -> {
			if(connection instanceof PlainConnection){
				return HTTP1.class;
			}else if(connection instanceof TLSConnection){
				String alpnProtocolName = ((TLSConnection) connection).getAlpnProtocol();
				if(alpnProtocolName == null || alpnProtocolName.equals(HTTP1.HTTP1_ALPN_NAME))
					return HTTP1.class;
			}
			return null;
		});
	}
}
