/*
 * Copyright (C) 2022 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2;

import org.omegazero.common.config.ConfigObject;
import org.omegazero.common.config.ConfigurationOption;
import org.omegazero.common.eventbus.EventBusSubscriber;
import org.omegazero.common.eventbus.SubscribeEvent;
import org.omegazero.net.socket.TLSConnection;
import org.omegazero.proxy.core.Proxy;

@EventBusSubscriber
public class HTTP2Plugin {


	@ConfigurationOption
	private boolean enable;


	@SubscribeEvent
	public String proxy_requiredFeatureSet() {
		return this.enable ? "tcp.*" : null;
	}

	@SubscribeEvent
	public String proxy_registerALPNOption() {
		return this.enable ? HTTP2.HTTP2_ALPN_NAME : null;
	}

	@SubscribeEvent
	public void onInit() {
		Proxy.getInstance().addHTTPEngineSelector((connection) -> {
			if(connection instanceof TLSConnection){
				String alpnProtocolName = ((TLSConnection) connection).getApplicationProtocol();
				if("h2".equals(alpnProtocolName))
					return HTTP2.class;
			}
			return null;
		});
	}
}
