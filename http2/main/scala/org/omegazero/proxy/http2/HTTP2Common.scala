/*
 * Copyright (C) 2023 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2;

import org.omegazero.http.h2.util.HTTP2Settings;
import org.omegazero.http.h2.streams.MessageStream;
import org.omegazero.proxy.config.HTTPEngineConfig;

import org.omegazero.http.h2.util.HTTP2Constants.*;

object HTTP2Common {

	def initSettings(config: HTTPEngineConfig): HTTP2Settings = {
		var settings = new HTTP2Settings();
		var maxFrameSize = config.optInt("maxFrameSize", 0);
		if(maxFrameSize > SETTINGS_MAX_FRAME_SIZE_MAX)
			throw new IllegalArgumentException("maxFrameSize is too large: " + maxFrameSize + " > " + SETTINGS_MAX_FRAME_SIZE_MAX);
		if(maxFrameSize > 0)
			settings.set(SETTINGS_MAX_FRAME_SIZE, maxFrameSize);

		var maxDynamicTableSize = config.optInt("maxDynamicTableSize", -1);
		if(maxDynamicTableSize >= 0)
			settings.set(SETTINGS_HEADER_TABLE_SIZE, maxDynamicTableSize);

		var initialWindowSize = config.optInt("initialWindowSize", 0);
		if(initialWindowSize > 0)
			settings.set(SETTINGS_INITIAL_WINDOW_SIZE, initialWindowSize);

		settings.set(SETTINGS_MAX_HEADER_LIST_SIZE, config.getMaxHeaderSize());
		settings.set(SETTINGS_MAX_CONCURRENT_STREAMS, config.optInt("maxConcurrentStreams", 100));
		return settings;
	}

	def initMessageStream(ms: MessageStream): Unit = {
		ms.setRequestSupplier(new org.omegazero.proxy.http.ProxyHTTPRequest(_, _, _, _, _, _));
		ms.setResponseSupplier(new org.omegazero.proxy.http.ProxyHTTPResponse(_, _, _));
	}
}
