/*
 * Copyright (C) 2023 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.omegazero.common.logging.Logger;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.http.common.{HTTPMessageTrailers, HTTPRequest, HTTPRequestData, HTTPResponse, HTTPResponseData};
import org.omegazero.http.h2.{HTTP2Client, HTTP2ConnectionError};
import org.omegazero.http.h2.hpack.HPackContext;
import org.omegazero.http.h2.streams.MessageStream;
import org.omegazero.http.h2.util.{HTTP2Constants, HTTP2Settings, HTTP2Util};
import org.omegazero.http.netutil.SocketConnectionWritable;
import org.omegazero.http.util.{AbstractHTTPClientStream, HTTPClient, HTTPClientStream, HTTPServer, WritableSocket};
import org.omegazero.proxy.config.HTTPEngineConfig;
import org.omegazero.proxy.http.HTTPCommon;
import org.omegazero.proxy.net.UpstreamServer;

import org.omegazero.http.h2.util.HTTP2Constants.*;

object ProxyHTTP2Client {

	final val logger = Logger.create();
}

class ProxyHTTP2Client(private val dsConnection: SocketConnection, private val userver: UpstreamServer, private val config: HTTPEngineConfig, server: HTTPServer)
		extends HTTP2Client(new SocketConnectionWritable(dsConnection),
			if server.isInstanceOf[ProxyHTTP2Server] then server.asInstanceOf[ProxyHTTP2Server].upstreamClientSettings else HTTP2Common.initSettings(config),
			if server.isInstanceOf[ProxyHTTP2Server] then server.asInstanceOf[ProxyHTTP2Server].hpackSession else new HPackContext.Session(), config.optBoolean("useHuffmanEncoding", true))
		with HTTPClient {

	private val logger = ProxyHTTP2Client.logger;

	private val remoteName = this.connection.getRemoteName();

	private var requestStreams = new java.util.concurrent.ConcurrentHashMap[Int, OutgoingRequestStream]();
	private var enablePush = true;

	this.dsConnection.on("writable", super.handleConnectionWindowUpdate _);

	super.start();
	// set a max table size value to be able to send requests before receiving a SETTINGS frame from the server
	this.hpack.setEncoderDynamicTableMaxSizeSettings(0);


	override def onSettingsUpdate(settings: HTTP2Settings): Unit = {
		super.onSettingsUpdate(settings);
		this.hpack.setEncoderDynamicTableMaxSizeCurrent(Math.min(this.settings.get(SETTINGS_HEADER_TABLE_SIZE), settings.get(SETTINGS_HEADER_TABLE_SIZE)));
	}


	override def receive(data: Array[Byte]): Unit = {
		super.processData(data);
	}

	override def getConnection(): WritableSocket = this.connection;
	
	override def close(): Unit = {
		this.dsConnection.destroy();
	}


	override def setServerPushEnabled(enabled: Boolean): Unit = {
		this.enablePush = enabled;
		this.settings.set(SETTINGS_ENABLE_PUSH, if enabled then 1 else 0);
		super.getControlStream().writeSettings(this.settings, SETTINGS_ENABLE_PUSH);
	}


	override def newRequest(request: HTTPRequest): HTTPClientStream = {
		if(this.dsConnection.hasDisconnected())
			return null;
		var ustream = super.createRequestStream();
		if(ustream == null)
			return null;
		if(logger.debug())
			logger.debug(this.remoteName, " Created new client request stream ", ustream.getStreamId(), " for request ", request.getAttachment(HTTPCommon.ATTACHMENT_KEY_REQUEST_ID));

		var reqstream = this.prepareStream(request, ustream);

		ustream.setOnPushPromise((promiseRequest) => {
			if(!this.enablePush)
				throw new HTTP2ConnectionError(HTTP2Constants.STATUS_PROTOCOL_ERROR, true);
			if(!reqstream.hasServerPushHandler)
				throw new HTTP2ConnectionError(HTTP2Constants.STATUS_CANCEL, true);
			var ppstream = super.handlePushPromise(promiseRequest);
			if(logger.debug())
				logger.debug(this.remoteName, " Created new client push promise stream ", ppstream.getStreamId(), " for a pushed request");
			var ppreqstream = this.prepareStream(promiseRequest, ppstream);
			reqstream.callOnServerPush(ppreqstream);
		});
		return reqstream;
	}

	override def getActiveRequests(): Collection[HTTPClientStream] = Collections.unmodifiableCollection(this.requestStreams.values());


	private def prepareStream(request: HTTPRequest, ustream: MessageStream): OutgoingRequestStream = {
		HTTP2Common.initMessageStream(ustream);

		var reqstream = new OutgoingRequestStream(request, ustream);
		reqstream.setReceiveData(true);

		ustream.setOnMessage((responsedata) => {
			var response = responsedata.getHttpMessage().asInstanceOf[HTTPResponse];
			reqstream.responseReceived(response);
			if(responsedata.isLastPacket())
				reqstream.callOnResponseEnded(null);
		});
		ustream.setOnData((responsedata) => {
			reqstream.callOnResponseData(responsedata.asInstanceOf[HTTPResponseData]);
			if(responsedata.isLastPacket())
				reqstream.callOnResponseEnded(null);
		});
		ustream.setOnTrailers((trailers) => {
			reqstream.callOnResponseEnded(trailers);
		});
		ustream.setOnDataFlushed(() => {
			reqstream.callOnWritable();
		});
		ustream.setOnClosed((status) => {
			if(logger.debug())
				logger.debug(this.remoteName, " Client request stream ", ustream.getStreamId(), " closed with status ", HTTP2ConnectionError.getStatusCodeName(status));
			this.requestStreams.remove(ustream.getStreamId());
			super.streamClosed(ustream);
			if(reqstream.getResponse() == null)
				reqstream.callOnError(new HTTP2ConnectionError(status, true));
		});

		this.requestStreams.put(ustream.getStreamId(), reqstream);
		return reqstream;
	}


	class OutgoingRequestStream(request: HTTPRequest, val ustream: MessageStream) extends AbstractHTTPClientStream(request, ProxyHTTP2Client.this) {

		private[http2] def hasServerPushHandler: Boolean = this.onServerPush != null;

		override def close(): Unit = {
			this.ustream.rst(HTTP2Constants.STATUS_CANCEL);
		}

		override def isClosed(): Boolean = this.ustream.isClosed();

		override def setReceiveData(receiveData: Boolean): Unit = this.ustream.setReceiveData(receiveData);

		override def startRequest(): Unit = {
			this.request.deleteHeader("transfer-encoding");
			this.request.deleteHeader("connection");
			this.request.deleteHeader("keep-alive");
			this.request.deleteHeader("upgrade");
			this.request.setHttpVersion(HTTP2.VERSION_NAME);
			this.ustream.sendHTTPMessage(this.request, false);
		}

		override def sendRequestData(data: Array[Byte], last: Boolean): Boolean = {
			if(data.length != 0 || last)
				return this.ustream.sendData(data, last);
			else
				return true;
		}

		override def endRequest(trailers: HTTPMessageTrailers): Unit = {
			if(trailers != null)
				this.ustream.sendTrailers(trailers);
			else
				super.endRequest(null);
		}
	}
}
