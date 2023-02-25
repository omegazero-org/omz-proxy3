/*
 * Copyright (C) 2023 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http1;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Consumer;

import org.omegazero.common.event.Tasks;
import org.omegazero.common.logging.Logger;
import org.omegazero.net.common.NetCommon;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.http.common.{HTTPRequest, HTTPRequestData, HTTPResponse, HTTPResponseData, InvalidHTTPMessageException, MessageStreamClosedException};
import org.omegazero.http.h1.{HTTP1MessageTransmitter, HTTP1RequestReceiver, HTTP1Util, MessageBodyDechunker};
import org.omegazero.http.netutil.SocketConnectionWritable;
import org.omegazero.http.util.{AbstractHTTPServerStream, HTTPServer, HTTPServerStream, HTTPStatus, WritableSocket};
import org.omegazero.proxy.config.HTTPEngineConfig;
import org.omegazero.proxy.http.HTTPCommon;

object ProxyHTTP1Server {

	private final val logger = Logger.create();

	private final val EMPTY_CHUNK = Array[Byte]('0', 0xd, 0xa, 0xd, 0xa);

	private final val ATTACHMENT_KEY_DECHUNKER = "_impl_dechunker";
}

class ProxyHTTP1Server(private val connection: SocketConnection, private val config: HTTPEngineConfig) extends HTTPServer {

	private val logger = ProxyHTTP1Server.logger;

	private val connectionWS: WritableSocket = new SocketConnectionWritable(this.connection);
	private val remoteName = this.connectionWS.getRemoteName();

	private var onNewRequest: Option[Consumer[HTTPServerStream]] = None;
	var onError: Option[(HTTPRequest, Int, String) => Unit] = None;

	private val transmitter = new HTTP1MessageTransmitter(this.connectionWS);
	private val requestReceiver = new HTTP1RequestReceiver(this.config.getMaxHeaderSize(), this.connection.isInstanceOf[org.omegazero.net.socket.TLSConnection]);

	private var currentRequestTimeoutRef: Object = null;
	private var currentRequestStream: Option[IncomingRequestStream] = None;
	private def currentRequestOrNull = if this.currentRequestStream.isDefined then this.currentRequestStream.get.getRequest() else null;

	this.connection.on("writable", () => {
		if(this.currentRequestStream.isDefined)
			this.currentRequestStream.get.callOnWritable();
	});


	override def receive(data: Array[Byte]): Unit = {
		try{
			this.processData(data);
		}catch{
			case e: InvalidHTTPMessageException => {
				if(logger.debug())
					logger.debug(this.remoteName, " HTTP error: ", if NetCommon.PRINT_STACK_TRACES then e else e.toString());
				this.onError.get (this.currentRequestOrNull, HTTPStatus.STATUS_BAD_REQUEST, if e.isMsgUserVisible() then e.getMessage() else HTTPCommon.MSG_BAD_REQUEST);
				if(this.currentRequestStream.isDefined)
					this.currentRequestStream.get.callOnError(e);
			}
			case e: Exception => {
				if(this.currentRequestStream.isDefined && !this.currentRequestStream.get.isClosed()){
					this.onError.get (this.currentRequestOrNull, HTTPStatus.STATUS_INTERNAL_SERVER_ERROR, HTTPCommon.MSG_SERVER_ERROR);
					logger.error(this.remoteName, " Error processing packet: ", e);
				}else
					throw e;
			}
		}
	}

	override def getConnection(): WritableSocket = this.connectionWS;
	
	override def close(): Unit = {
		this.connection.destroy();
		if(this.currentRequestStream.isDefined)
			this.currentRequestStream.get.close();
	}


	override def onNewRequest(callback: Consumer[HTTPServerStream]): Unit = this.onNewRequest = Some(callback);

	override def getActiveRequests(): Collection[HTTPServerStream] =
		if this.currentRequestStream.isDefined then Collections.singleton(this.currentRequestStream.get) else Collections.emptySet();


	override def respond(request: HTTPRequest, responsedata: HTTPResponseData): Unit = {
		if(request != null && request.hasResponse())
			return;
		if(request != this.currentRequestOrNull)
			throw new IllegalArgumentException("Can only respond to the current request");
		Tasks.I.clear(this.currentRequestTimeoutRef);

		var response = responsedata.getHttpMessage();
		if(!HTTPCommon.setRequestResponse(request, response))
			return;
		logger.debug(this.remoteName, " Responding with status ", response.getStatus());

		if(!this.connection.isConnected())
			return;

		if(!response.headerExists("connection"))
			response.setHeader("connection", "close");
		response.deleteHeader("transfer-encoding");

		var data = HTTPCommon.prepareHTTPResponse(request, response, responsedata.getData());
		if(request != null){
			request.synchronized {
				if(this.currentRequestStream.get.requestEnded){ // request incl data fully received
					this.currentRequestStream.get.startResponse(response);
					this.currentRequestStream.get.sendResponseData(data, true);
				}else
					this.currentRequestStream.get.pendingResponse = Some(new HTTPResponseData(response, data));
			}
		}else{
			if(this.writeHTTPMsg(response))
				this.connection.write(data);
			// currentRequestStream is already empty
		}
	}

	override def respond(request: HTTPRequest, status: Int, data: Array[Byte], headers: String*): Unit = throw new UnsupportedOperationException();


	private def processData(data: Array[Byte]): Unit = {
		var remainingData = data;
		if(this.currentRequestStream.isEmpty){
			if(this.currentRequestTimeoutRef == null)
				this.currentRequestTimeoutRef = Tasks.I.timeout(this.handleRequestTimeout _, this.config.getRequestTimeout()).daemon();
			var offset = this.requestReceiver.receive(remainingData, 0);
			if(offset < 0)
				return;

			Tasks.I.clear(this.currentRequestTimeoutRef);

			var request: HTTPRequest = this.requestReceiver.get(new org.omegazero.proxy.http.ProxyHTTPRequest(_, _, _, _, _, _));
			this.requestReceiver.reset();

			if(request.getAuthority() == null)
				throw new InvalidHTTPMessageException("Missing Host header", true);

			var reqstream = new IncomingRequestStream(request);
			reqstream.setReceiveData(true);
			this.currentRequestStream = Some(reqstream);

			var dechunker = new MessageBodyDechunker(request, (reqdata) => {
				var last = reqdata.length == 0;
				reqstream.callOnRequestData(new HTTPRequestData(request, last, reqdata));
				if(last){
					reqstream.callOnRequestEnded(null);
					request.setAttachment(ProxyHTTP1Server.ATTACHMENT_KEY_DECHUNKER, null);
					if(reqstream.pendingResponse.isDefined){
						reqstream.startResponse(reqstream.pendingResponse.get.getHttpMessage());
						reqstream.sendResponseData(reqstream.pendingResponse.get.getData(), true);
					}
				}
			});
			request.setAttachment(ProxyHTTP1Server.ATTACHMENT_KEY_DECHUNKER, dechunker);

			this.onNewRequest.get.accept(reqstream);

			remainingData = Arrays.copyOfRange(remainingData, offset, remainingData.length);
		}
		if(this.currentRequestStream.get.requestEnded){
			if(logger.debug())
				logger.debug(this.remoteName, " Received data after request ended");
			this.close();
			return;
		}
		var dechunker = this.currentRequestOrNull.getAttachment(ProxyHTTP1Server.ATTACHMENT_KEY_DECHUNKER).asInstanceOf[MessageBodyDechunker];
		try{
			dechunker.addData(remainingData);
		}catch{
			case e: Exception => {
				dechunker.end();
				throw e;
			}
		}
	}

	private def handleRequestTimeout(): Unit = {
		logger.debug(this.remoteName, " Request timeout");
		try{
			assert(this.currentRequestStream.isEmpty);
			this.onError.get (null, HTTPStatus.STATUS_REQUEST_TIMEOUT, HTTPCommon.MSG_REQUEST_TIMEOUT);
			this.requestReceiver.reset();
		}catch{
			case e: Exception => {
				logger.error(this.remoteName, " Error while handling request timeout: ", e);
				this.close();
			}
		}
	}


	private def writeHTTPMsg(msg: HTTPResponse): Boolean = {
		if(this.connection.isConnected() && !this.connection.isWritable()){
			logger.warn(this.remoteName, " Tried to write HTTP message on blocked socket; destroying socket [DoS mitigation]");
			this.close();
			return false;
		}
		this.transmitter.send(msg);
		return true;
	}


	class IncomingRequestStream(request: HTTPRequest) extends AbstractHTTPServerStream(request, ProxyHTTP1Server.this) {

		private var chunkedTransfer = false;

		var pendingResponse: Option[HTTPResponseData] = None;
		def requestEnded = !this.request.hasAttachment(ProxyHTTP1Server.ATTACHMENT_KEY_DECHUNKER);

		override def close(reason: MessageStreamClosedException.CloseReason): Unit = {
			ProxyHTTP1Server.this.connection.destroy();
			this.closed = true;
		}

		override def setReceiveData(receiveData: Boolean): Unit = scala.util.control.Exception.ignoring(classOf[Exception]){ ProxyHTTP1Server.this.connection.setReadBlock(!receiveData); }

		override def startResponse(response: HTTPResponse): Unit = {
			if(!this.requestEnded)
				throw new IllegalStateException("Cannot send response before request ended");
			if(response.isChunkedTransfer()){
				response.setHeader("transfer-encoding", "chunked");
				this.chunkedTransfer = true;
			}
			response.setHttpVersion(HTTP1.VERSION_NAME);
			ProxyHTTP1Server.this.writeHTTPMsg(response);
		}

		override def sendResponseData(data: Array[Byte], last: Boolean): Boolean = {
			if(!this.requestEnded)
				throw new IllegalStateException("Cannot send response data before request ended");
			if(data.length > 0){
				if(this.chunkedTransfer)
					ProxyHTTP1Server.this.connection.write(HTTP1Util.toChunk(data));
				else
					ProxyHTTP1Server.this.connection.write(data);
			}
			if(last){
				if(this.chunkedTransfer)
					ProxyHTTP1Server.this.connection.write(ProxyHTTP1Server.EMPTY_CHUNK);
				this.closed = true;
				ProxyHTTP1Server.this.currentRequestStream = None;
			}
			return ProxyHTTP1Server.this.connection.isWritable();
		}
	}
}
