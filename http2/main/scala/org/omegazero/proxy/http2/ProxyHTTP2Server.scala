/*
 * Copyright (C) 2023 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Consumer;

import org.omegazero.common.event.Tasks;
import org.omegazero.common.logging.Logger;
import org.omegazero.net.common.NetCommon;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.http.common.{HTTPMessageTrailers, HTTPRequest, HTTPRequestData, HTTPResponse, HTTPResponseData, MessageStreamClosedException};
import org.omegazero.http.h2.{HTTP2ConnectionError, HTTP2Endpoint};
import org.omegazero.http.h2.hpack.HPackContext;
import org.omegazero.http.h2.streams.{ControlStream, HTTP2Stream, MessageStream};
import org.omegazero.http.h2.util.{HTTP2Constants, HTTP2Settings, HTTP2Util};
import org.omegazero.http.netutil.SocketConnectionWritable;
import org.omegazero.http.util.{AbstractHTTPServerStream, HTTPServer, HTTPServerStream, HTTPStatus, WritableSocket};
import org.omegazero.proxy.config.HTTPEngineConfig;
import org.omegazero.proxy.http.HTTPCommon;

import org.omegazero.http.h2.util.HTTP2Constants.*;

object ProxyHTTP2Server {

	private final val logger = Logger.create();

}

class ProxyHTTP2Server(private val dsConnection: SocketConnection, private val config: HTTPEngineConfig)
		extends HTTP2Endpoint(new SocketConnectionWritable(dsConnection), HTTP2Common.initSettings(config), new HPackContext.Session(), config.optBoolean("useHuffmanEncoding", true))
		with HTTPServer {

	private val logger = ProxyHTTP2Server.logger;

	private val remoteName = this.connection.getRemoteName();

	private val disablePromiseRequestLog = config.optBoolean("disablePromiseRequestLog", config.isDisableDefaultRequestLog());

	private var onNewRequest: Option[Consumer[HTTPServerStream]] = None;
	var onError: Option[(HTTPRequest, Int, String) => Unit] = None;

	private var prefaceReceived = false;
	private var nextStreamId = 2;
	private var requestStreams = new java.util.concurrent.ConcurrentHashMap[Int, IncomingRequestStream]();

	var upstreamClientSettings = new HTTP2Settings(this.settings);
	def hpackSession = this.hpack.getSession();

	this.closeWaitTimeout = config.optInt("closeWaitTimeout", 5) * 1000000000L;

	this.hpack.setEncoderDynamicTableMaxSizeCurrent(this.settings.get(SETTINGS_HEADER_TABLE_SIZE));

	this.dsConnection.on("writable", super.handleConnectionWindowUpdate _);



	override def newStreamForFrame(streamId: Int, frameType: Int, flags: Int, payload: Array[Byte]): HTTP2Stream = {
		if(frameType == FRAME_TYPE_HEADERS){
			if((streamId & 1) == 0 || streamId <= this.highestStreamId)
				throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR);
			super.checkRemoteCreateStream();
			var cs = super.getControlStream();
			var mstream = new MessageStream(streamId, this.connection, cs, this.hpack);
			HTTP2Common.initMessageStream(mstream);
			logger.debug(this.remoteName, " Created new stream ", mstream.getStreamId(), " for HEADERS frame");
			var baseCloseHandler: Consumer[Integer] = (status) => {
				logger.debug(this.remoteName, " Request stream ", mstream.getStreamId(), " closed with status ", HTTP2ConnectionError.getStatusCodeName(status));
				this.requestStreams.remove(mstream.getStreamId());
				super.streamClosed(mstream);
			};
			mstream.setOnMessage((requestdata) => {
				var request = requestdata.getHttpMessage().asInstanceOf[HTTPRequest];
				var endStream = requestdata.isLastPacket();
				try{
					this.processHTTPRequest(mstream, request, endStream, baseCloseHandler);
				}catch{
					case e: HTTP2ConnectionError => throw e;
					case e: Exception => {
						logger.error(this.remoteName, " Error while processing request: ", e);
						throw new HTTP2ConnectionError(STATUS_INTERNAL_ERROR, true);
					}
				}
			});
			mstream.setOnClosed(baseCloseHandler);
			return mstream;
		}
		return null;
	}


	override def receive(data: Array[Byte]): Unit = {
		var index = 0;
		while(index >= 0 && index < data.length){
			if(this.prefaceReceived){
				index = super.processData0(data, index);
			}else if(HTTP2Util.isValidClientPreface(data)){
				index += HTTP2Util.getClientPrefaceLength();
				this.prefaceReceived = true;
				var cs = new ControlStream(this.connection, this.settings);
				super.registerStream(cs);
				cs.setOnSettingsUpdate((settings) => {
					this.hpack.setEncoderDynamicTableMaxSizeSettings(settings.get(SETTINGS_HEADER_TABLE_SIZE));
					if(settings.get(SETTINGS_ENABLE_PUSH) == 0)
						this.upstreamClientSettings.set(SETTINGS_ENABLE_PUSH, 0);
				});
				cs.setOnWindowUpdate(super.handleConnectionWindowUpdate _);
				cs.writeSettings(this.settings);
			}else{
				logger.debug(this.remoteName, " Invalid client preface");
				this.dsConnection.destroy();
				index = -1;
			}
		}
	}

	override def getConnection(): WritableSocket = this.connection;
	
	override def close(): Unit = {
		this.dsConnection.destroy();
	}


	override def isServerPushEnabled(): Boolean = this.getControlStream().getRemoteSettings().get(HTTP2Constants.SETTINGS_ENABLE_PUSH) == 1;


	override def onNewRequest(callback: Consumer[HTTPServerStream]): Unit = this.onNewRequest = Some(callback);

	override def getActiveRequests(): Collection[HTTPServerStream] = Collections.unmodifiableCollection(this.requestStreams.values());


	override def respond(request: HTTPRequest, responsedata: HTTPResponseData): Unit = {
		if(!request.hasAttachment(MessageStream.ATTACHMENT_KEY_STREAM_ID))
			throw new IllegalArgumentException("request is not a HTTP/2 request (missing stream ID attachment)");
		var streamId = request.getAttachment(MessageStream.ATTACHMENT_KEY_STREAM_ID).asInstanceOf[Int];

		var response = responsedata.getHttpMessage();
		if(!HTTPCommon.setRequestResponse(request, response))
			return;
		var reqstream = this.requestStreams.get(streamId);
		if(reqstream == null)
			throw new IllegalStateException("Invalid stream");
		logger.debug(this.remoteName, " Responding with status ", response.getStatus());

		response.deleteHeader("transfer-encoding");
		response.deleteHeader("connection");
		response.deleteHeader("keep-alive");
		response.deleteHeader("upgrade");

		var data = HTTPCommon.prepareHTTPResponse(request, response, responsedata.getData());
		if(!reqstream.clientStream.isExpectingResponse())
			return;
		request.synchronized {
			if(reqstream.requestEnded){ // request incl data fully received
				reqstream.startResponse(response);
				reqstream.sendResponseData(data, true);
			}else
				reqstream.pendingResponse = Some(new HTTPResponseData(response, data));
		}
	}

	override def respond(request: HTTPRequest, status: Int, data: Array[Byte], headers: String*): Unit = throw new UnsupportedOperationException();


	private def processHTTPRequest(clientStream: MessageStream, request: HTTPRequest, endStream: Boolean, baseCloseHandler: Consumer[Integer]) = {
		var reqstream = new IncomingRequestStream(request, clientStream);
		reqstream.setReceiveData(true);

		clientStream.setOnData((requestdata) => {
			reqstream.callOnRequestData(requestdata.asInstanceOf[HTTPRequestData]);
			if(requestdata.isLastPacket())
				reqstream.callOnRequestEnded(null);
		});
		clientStream.setOnTrailers((trailers) => {
			reqstream.callOnRequestEnded(trailers);
		});
		clientStream.setOnDataFlushed(() => {
			reqstream.callOnWritable();
		});
		clientStream.setOnClosed((status) => {
			baseCloseHandler.accept(status);
			if(status != STATUS_NO_ERROR)
				reqstream.callOnError(new MessageStreamClosedException(HTTP2Common.http2StatusToCloseReason(status)));
		});

		this.requestStreams.put(clientStream.getStreamId(), reqstream);

		this.onNewRequest.get.accept(reqstream);
		if(endStream)
			reqstream.callOnRequestEnded(null);
	}

	private def nextPushStreamId: Int = {
		var streamId = this.nextStreamId;
		this.nextStreamId += 2;
		return streamId;
	}


	class IncomingRequestStream(request: HTTPRequest, val clientStream: MessageStream) extends AbstractHTTPServerStream(request, ProxyHTTP2Server.this) {

		var pendingResponse: Option[HTTPResponseData] = None;
		var requestEnded = false;


		override def callOnRequestEnded(trailers: HTTPMessageTrailers) = {
			super.callOnRequestEnded(trailers);
			this.requestEnded = true;
			if(this.pendingResponse.isDefined){
				this.startResponse(this.pendingResponse.get.getHttpMessage());
				this.sendResponseData(this.pendingResponse.get.getData(), true);
			}
		}


		override def close(reason: MessageStreamClosedException.CloseReason): Unit = {
			this.clientStream.rst(HTTP2Common.closeReasonToHttp2Status(reason));
		}

		override def isClosed(): Boolean = this.clientStream.isClosed();

		override def setReceiveData(receiveData: Boolean): Unit = this.clientStream.setReceiveData(receiveData);

		override def startServerPush(promiseRequest: HTTPRequest): HTTPServerStream = {
			var cs = ProxyHTTP2Server.super.getControlStream();
			var ppstream = new MessageStream(ProxyHTTP2Server.this.nextPushStreamId, ProxyHTTP2Server.this.connection, cs, ProxyHTTP2Server.this.hpack);
			HTTP2Common.initMessageStream(ppstream);
			ppstream.preparePush(false);
			ProxyHTTP2Server.super.registerStream(ppstream);
			logger.debug(ProxyHTTP2Server.this.remoteName, " Created new push promise stream ", ppstream.getStreamId(), " for promise request ",
					promiseRequest.getAttachment(HTTPCommon.ATTACHMENT_KEY_REQUEST_ID));

			promiseRequest.setAttachment(MessageStream.ATTACHMENT_KEY_STREAM_ID, ppstream.getStreamId());

			var reqstream = new IncomingRequestStream(promiseRequest, ppstream);
			reqstream.setReceiveData(true);

			ProxyHTTP2Server.this.requestStreams.put(ppstream.getStreamId(), reqstream);
			ppstream.setOnClosed((status) => {
				if(logger.debug())
					logger.debug(ProxyHTTP2Server.this.remoteName, " Push promise request stream ", ppstream.getStreamId(), " closed with status ", HTTP2ConnectionError.getStatusCodeName(status));
				ProxyHTTP2Server.this.requestStreams.remove(ppstream.getStreamId());
				ProxyHTTP2Server.super.streamClosed(ppstream);
			});

			this.clientStream.sendPushPromise(ppstream.getStreamId(), promiseRequest);
			return reqstream;
		}

		override def startResponse(response: HTTPResponse): Unit = {
			response.setHttpVersion(HTTP2.VERSION_NAME);
			this.clientStream.sendHTTPMessage(response, false);
		}

		override def sendResponseData(data: Array[Byte], last: Boolean): Boolean = {
			this.clientStream.sendData(data, last);
		}

		override def endResponse(trailers: HTTPMessageTrailers): Unit = {
			if(trailers != null)
				this.clientStream.sendTrailers(trailers);
			else
				super.endResponse(null);
		}
	}
}
