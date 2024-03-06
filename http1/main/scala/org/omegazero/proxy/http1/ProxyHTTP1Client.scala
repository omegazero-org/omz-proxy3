/*
 * Copyright (C) 2023 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http1;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.omegazero.common.logging.Logger;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.http.common.{HTTPRequest, HTTPRequestData, HTTPResponse, HTTPResponseData, InvalidHTTPMessageException, MessageStreamClosedException};
import org.omegazero.http.h1.{HTTP1MessageTransmitter, HTTP1ResponseReceiver, HTTP1Util, MessageBodyDechunker};
import org.omegazero.http.netutil.SocketConnectionWritable;
import org.omegazero.http.util.{AbstractHTTPClientStream, HTTPClient, HTTPClientStream, HTTPServer, WritableSocket};
import org.omegazero.proxy.config.HTTPEngineConfig;
import org.omegazero.proxy.net.UpstreamServer;

object ProxyHTTP1Client {

	final val logger = Logger.create();

	final val EMPTY_CHUNK = Array[Byte]('0', 0xd, 0xa, 0xd, 0xa);

	final val ATTACHMENT_KEY_DECHUNKER = "_impl_dechunker";
}

class ProxyHTTP1Client(private val connection: SocketConnection, private val userver: UpstreamServer, private val config: HTTPEngineConfig, server: HTTPServer) extends HTTPClient {

	private val logger = ProxyHTTP1Client.logger;

	private val connectionWS: WritableSocket = new SocketConnectionWritable(this.connection);
	private val remoteName = this.connectionWS.getRemoteName();

	private val transmitter = new HTTP1MessageTransmitter(this.connectionWS);
	private val responseReceiver = new HTTP1ResponseReceiver(this.config.getMaxHeaderSize());

	private var currentRequestStream: OutgoingRequestStream = null;

	this.connection.on("writable", () => {
		if(this.currentRequestStream != null)
			this.currentRequestStream.callOnWritable();
	});


	override def receive(data: Array[Byte]): Unit = {
		this.processResponseData(data);
	}

	override def getConnection(): WritableSocket = this.connectionWS;
	
	override def close(): Unit = {
		this.connection.destroy();
		if(this.currentRequestStream != null)
			this.currentRequestStream.close();
	}


	override def newRequest(request: HTTPRequest): HTTPClientStream = {
		if(this.currentRequestStream != null || this.connection.hasDisconnected())
			return null;
		if(request.isChunkedTransfer())
			request.setHeader("transfer-encoding", "chunked");
		this.currentRequestStream = new OutgoingRequestStream(request);
		return this.currentRequestStream;
	}

	override def getActiveRequests(): Collection[HTTPClientStream] = if this.currentRequestStream != null then Collections.singleton(this.currentRequestStream) else Collections.emptySet();

	override def getMaxConcurrentRequestCount(): Int = 1;


	private def processResponseData(data: Array[Byte]): Unit = {
		var remainingData = data;
		if(this.currentRequestStream == null){
			logger.debug(this.remoteName, " Received unexpected data on connection");
			this.close();
			return;
		}

		if(this.currentRequestStream.getResponse() == null){
			var offset = this.responseReceiver.receive(remainingData, 0);
			if(offset < 0)
				return;

			var response: HTTPResponse = this.responseReceiver.get(new org.omegazero.proxy.http.ProxyHTTPResponse(_, _, _));
			this.responseReceiver.reset();

			if(response.isIntermediateMessage()){
				this.currentRequestStream.responseReceived(response);
				return;
			}

			var dechunker = new MessageBodyDechunker(response, (resdata) => {
				var last = resdata.length == 0;
				this.currentRequestStream.callOnResponseData(new HTTPResponseData(response, last, resdata));
				if(last){
					this.currentRequestStream.callOnResponseEnded(null);
					response.setAttachment(ProxyHTTP1Client.ATTACHMENT_KEY_DECHUNKER, null);
					this.currentRequestStream = null;
					if("close".equals(response.getHeader("connection")))
						this.connection.close();
				}
			});
			response.setAttachment(ProxyHTTP1Client.ATTACHMENT_KEY_DECHUNKER, dechunker);

			this.currentRequestStream.responseReceived(response);

			remainingData = Arrays.copyOfRange(remainingData, offset, remainingData.length);
		}
		this.currentRequestStream.getResponse().getAttachment(ProxyHTTP1Client.ATTACHMENT_KEY_DECHUNKER).asInstanceOf[MessageBodyDechunker].addData(remainingData);
	}


	private def writeHTTPMsg(msg: HTTPRequest): Boolean = {
		if(this.connection.isConnected() && !this.connection.isWritable()){
			logger.warn(this.remoteName, " Tried to write HTTP message on blocked socket; destroying socket [DoS mitigation]");
			this.close();
			return false;
		}
		this.transmitter.send(msg);
		return true;
	}


	class OutgoingRequestStream(request: HTTPRequest) extends AbstractHTTPClientStream(request, ProxyHTTP1Client.this) {

		override def close(reason: MessageStreamClosedException.CloseReason): Unit = {
			ProxyHTTP1Client.this.connection.destroy();
			this.closed = true;
		}

		override def setReceiveData(receiveData: Boolean): Unit = scala.util.control.Exception.ignoring(classOf[Exception]){ ProxyHTTP1Client.this.connection.setReadBlock(!receiveData); }

		override def startRequest(): Unit = {
			this.request.setHttpVersion(HTTP1.VERSION_NAME);
			ProxyHTTP1Client.this.writeHTTPMsg(request);
		}

		override def sendRequestData(data: Array[Byte], last: Boolean): Boolean = {
			if(data.length > 0){
				if(this.request.isChunkedTransfer())
					ProxyHTTP1Client.this.connection.write(HTTP1Util.toChunk(data));
				else
					ProxyHTTP1Client.this.connection.write(data);
			}
			if(last){
				if(this.request.isChunkedTransfer())
					ProxyHTTP1Client.this.connection.write(ProxyHTTP1Client.EMPTY_CHUNK);
				this.setReceiveData(true);
			}
			return ProxyHTTP1Client.this.connection.isWritable();
		}
	}
}
