/*
 * Copyright (C) 2023 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2;

import org.omegazero.http.common.HTTPMessage;
import org.omegazero.http.common.MessageStreamClosedException.CloseReason;
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

	def http2StatusToCloseReason(status: Int): CloseReason = {
		status match {
			case STATUS_PROTOCOL_ERROR | STATUS_FLOW_CONTROL_ERROR | STATUS_SETTINGS_TIMEOUT | STATUS_FRAME_SIZE_ERROR | STATUS_COMPRESSION_ERROR => CloseReason.PROTOCOL_ERROR
			case STATUS_INTERNAL_ERROR => CloseReason.INTERNAL_ERROR
			case STATUS_CANCEL => CloseReason.CANCEL
			case STATUS_REFUSED_STREAM => CloseReason.REFUSED
			case STATUS_ENHANCE_YOUR_CALM => CloseReason.ENHANCE_YOUR_CALM
			case STATUS_HTTP_1_1_REQUIRED => CloseReason.PROTOCOL_DOWNGRADE
			case _ => CloseReason.UNKNOWN
		}
	}

	def closeReasonToHttp2Status(reason: CloseReason): Int = {
		reason match {
			case CloseReason.PROTOCOL_ERROR => STATUS_PROTOCOL_ERROR
			case CloseReason.INTERNAL_ERROR => STATUS_INTERNAL_ERROR
			case CloseReason.REFUSED => STATUS_REFUSED_STREAM
			case CloseReason.ENHANCE_YOUR_CALM => STATUS_ENHANCE_YOUR_CALM
			case CloseReason.PROTOCOL_DOWNGRADE => STATUS_HTTP_1_1_REQUIRED
			case _ => STATUS_CANCEL
		}
	}

	def deleteHttp1Headers(msg: HTTPMessage): Unit = {
		msg.deleteHeader("transfer-encoding");
		msg.deleteHeader("connection");
		msg.deleteHeader("keep-alive");
		msg.deleteHeader("upgrade");
	}
}
