/*
 * Copyright (C) 2022 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2;

import org.omegazero.http.h2.HTTP2Client;
import org.omegazero.http.h2.hpack.HPackContext.Session;
import org.omegazero.http.h2.util.HTTP2Constants;
import org.omegazero.http.h2.util.HTTP2Settings;
import org.omegazero.http.netutil.SocketConnectionWritable;
import org.omegazero.net.socket.SocketConnection;

public class ProxyHTTP2Client extends HTTP2Client {


	public ProxyHTTP2Client(SocketConnection connection, HTTP2Settings settings, Session hpackSession, boolean useHuffmanEncoding) {
		super(new SocketConnectionWritable(connection), settings, hpackSession, useHuffmanEncoding);

		connection.on("writable", super::handleConnectionWindowUpdate);
	}


	@Override
	public void start() {
		super.start();
		// set a max table size value to be able to send requests before receiving a SETTINGS frame from the server
		super.hpack.setEncoderDynamicTableMaxSizeSettings(0);
	}


	@Override
	protected void onSettingsUpdate(HTTP2Settings settings) {
		super.onSettingsUpdate(settings);
		super.hpack.setEncoderDynamicTableMaxSizeCurrent(
				Math.min(super.settings.get(HTTP2Constants.SETTINGS_HEADER_TABLE_SIZE), settings.get(HTTP2Constants.SETTINGS_HEADER_TABLE_SIZE)));
	}
}
