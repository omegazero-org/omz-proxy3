/*
 * Copyright (C) 2022 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Covered Software is provided under this License on an "as is" basis, without warranty of any kind,
 * either expressed, implied, or statutory, including, without limitation, warranties that the Covered Software
 * is free of defects, merchantable, fit for a particular purpose or non-infringing.
 * The entire risk as to the quality and performance of the Covered Software is with You.
 */
package org.omegazero.proxy.http1;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import org.omegazero.common.event.Tasks;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.http.common.HTTPMessage;
import org.omegazero.http.common.HTTPRequest;
import org.omegazero.http.common.HTTPRequestData;
import org.omegazero.http.common.HTTPResponse;
import org.omegazero.http.common.HTTPResponseData;
import org.omegazero.http.common.InvalidHTTPMessageException;
import org.omegazero.http.h1.HTTP1MessageTransmitter;
import org.omegazero.http.h1.HTTP1RequestReceiver;
import org.omegazero.http.h1.HTTP1ResponseReceiver;
import org.omegazero.http.h1.HTTP1Util;
import org.omegazero.http.h1.MessageBodyDechunker;
import org.omegazero.net.common.NetCommon;
import org.omegazero.net.socket.AbstractSocketConnection;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.net.socket.TLSConnection;
import org.omegazero.proxy.config.HTTPEngineConfig;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.core.ProxyEvents;
import org.omegazero.proxy.http.HTTPCommon;
import org.omegazero.proxy.http.HTTPEngine;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.HTTPEngineResponderMixin;
import org.omegazero.proxy.util.ProxyUtil;

import static org.omegazero.http.util.HTTPStatus.*;

public class HTTP1 implements HTTPEngine, HTTPEngineResponderMixin {

	private static final Logger logger = LoggerUtil.createLogger();

	static final String HTTP1_ALPN_NAME = "http/1.1";
	private static final String[] HTTP1_ALPN = new String[] { HTTP1_ALPN_NAME };
	private static final byte[] EMPTY_CHUNK = new byte[] { '0', 0xd, 0xa, 0xd, 0xa };

	private static final String ATTACHMENT_KEY_DECHUNKER = "engine_dechunker";
	private static final String ATTACHMENT_KEY_PENDING_RESPONSE = "engine_pendingresponse";
	private static final String ATTACHMENT_KEY_UPROTOCOL = "engine_otherProtocol";
	private static final String ATTACHMENT_KEY_RESPONSE_TIMEOUT = "engine_responseTimeoutId";

	private static final String CONNDBG = "dbg";


	private final SocketConnection downstreamConnection;
	private final Proxy proxy;
	private final HTTPEngineConfig config;

	private final String downstreamConnectionDbgstr;
	private final boolean downstreamSecurity;

	private final HTTP1RequestReceiver requestReceiver;
	private final HTTP1ResponseReceiver responseReceiver;
	private final HTTP1MessageTransmitter transmitter;

	private boolean downstreamClosed;

	private HTTPRequest currentRequest;
	private long currentRequestTimeoutId = -1;
	private UpstreamServer currentUpstreamServer;
	private AbstractSocketConnection currentUpstreamConnection;
	private Map<UpstreamServer, AbstractSocketConnection> upstreamConnections = new java.util.concurrent.ConcurrentHashMap<>();

	public HTTP1(SocketConnection downstreamConnection, Proxy proxy, HTTPEngineConfig config) {
		this.downstreamConnection = Objects.requireNonNull(downstreamConnection);
		this.proxy = Objects.requireNonNull(proxy);
		this.config = config;

		this.downstreamConnectionDbgstr = this.proxy.debugStringForConnection(this.downstreamConnection, null);
		this.downstreamSecurity = this.downstreamConnection instanceof TLSConnection;

		this.requestReceiver = new HTTP1RequestReceiver(this.config.getMaxHeaderSize(), this.downstreamSecurity);
		this.responseReceiver = new HTTP1ResponseReceiver(this.config.getMaxHeaderSize());
		this.transmitter = new HTTP1MessageTransmitter();
	}


	@Override
	public synchronized void processData(byte[] data) {
		try{
			this.processPacket(data);
		}catch(InvalidHTTPMessageException e){
			logger.debug(this.downstreamConnectionDbgstr, " HTTP error: ", NetCommon.PRINT_STACK_TRACES ? e : e.toString());
			this.respondError(this.currentRequest, STATUS_BAD_REQUEST, e.isMsgUserVisible() ? e.getMessage() : HTTPCommon.MSG_BAD_REQUEST);
		}catch(Exception e){
			if(this.currentRequest != null){
				this.respondInternalError(e);
				logger.error(this.downstreamConnectionDbgstr, " Error while processing packet: ", e);
				if(this.currentRequest != null) // response is still pending after respond above
					this.endRequest(this.currentRequest);
				this.downstreamConnection.close();
			}else
				throw e;
		}
	}

	@Override
	public void close() {
		this.downstreamClosed = true;
		for(SocketConnection uconn : this.upstreamConnections.values())
			uconn.close();
		if(this.currentRequestTimeoutId >= 0)
			Tasks.clear(this.currentRequestTimeoutId);
		if(this.currentRequest != null && this.currentRequest.hasAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT))
			Tasks.clear((long) this.currentRequest.getAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT));
	}

	@Override
	public SocketConnection getDownstreamConnection() {
		return this.downstreamConnection;
	}

	@Override
	public synchronized void respond(HTTPRequest request, HTTPResponseData responsedata) {
		if(request != null && request.hasResponse())
			return;
		if(request != this.currentRequest)
			throw new IllegalArgumentException("Can only respond to the current request");
		if(!this.downstreamConnection.isConnected())
			return;
		if(this.currentRequestTimeoutId >= 0){ // this is possible when receiving invalid requests
			Tasks.clear(this.currentRequestTimeoutId);
			this.currentRequestTimeoutId = -1;
		}

		HTTPResponse response = responsedata.getHttpMessage();
		if(!HTTPCommon.setRequestResponse(request, response))
			return;
		logger.debug(this.downstreamConnectionDbgstr, " Responding with status ", response.getStatus());

		if(!response.headerExists("connection"))
			response.setHeader("connection", "close");
		response.deleteHeader("transfer-encoding");

		byte[] data = responsedata.getData();
		data = HTTPCommon.prepareHTTPResponse(request, response, data);
		if(request != null){
			synchronized(request){
				if(request.hasAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT))
					Tasks.clear((long) request.getAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT));
				if(!request.hasAttachment(ATTACHMENT_KEY_DECHUNKER)){
					this.writeHTTPMsg(this.downstreamConnection, response, data);
					this.currentRequest = null;
					this.responseReceiver.reset();
				}else
					request.setAttachment(ATTACHMENT_KEY_PENDING_RESPONSE, new HTTPResponseData(response, data));
			}
		}else{
			this.writeHTTPMsg(this.downstreamConnection, response, data);
			// currentRequest is already null
		}
	}

	@Override
	public void respond(HTTPRequest request, int status, byte[] data, String... headers) {
		this.respondEx(this.proxy, request, status, data, headers);
	}

	@Override
	public void respondError(HTTPRequest request, int status, String title, String message, String... headers) {
		this.respondError(this.proxy, request, status, title, message, headers);
	}

	private void respondUNetError(HTTPRequest request, int status, String message, SocketConnection uconn, UpstreamServer userver) {
		this.respondUNetError(this.proxy, request, status, message, uconn, userver);
	}

	private void respondInternalError(Throwable e) {
		try{
			this.respondError(this.currentRequest, STATUS_INTERNAL_SERVER_ERROR, HTTPCommon.MSG_SERVER_ERROR);
		}catch(Throwable e2){
			if(e != null)
				e.addSuppressed(e2);
		}
	}

	@Override
	public String getHTTPVersionName() {
		return "HTTP/1.1";
	}


	private void processPacket(byte[] data) throws InvalidHTTPMessageException {
		assert Thread.holdsLock(this);
		if(this.currentRequest == null){ // expecting a new request
			if(this.currentRequestTimeoutId < 0)
				this.currentRequestTimeoutId = Tasks.timeout(this::handleRequestTimeout, this.config.getRequestTimeout()).daemon().getId();

			int offset;
			try{
				offset = this.requestReceiver.receive(data, 0);
			}catch(InvalidHTTPMessageException e){
				this.proxy.dispatchEvent(ProxyEvents.INVALID_HTTP_REQUEST, this.downstreamConnection, data);
				throw e;
			}
			if(offset < 0)
				return;

			Tasks.clear(this.currentRequestTimeoutId);

			HTTPRequest request = this.requestReceiver.get(org.omegazero.proxy.http.ProxyHTTPRequest::new);
			this.requestReceiver.reset();
			this.currentRequest = request;
			this.currentUpstreamServer = null;
			this.currentUpstreamConnection = null;
			request.setHttpResponder(this);

			data = Arrays.copyOfRange(data, offset, data.length);

			String requestId = HTTPCommon.requestId(this.downstreamConnection);
			request.setAttachment(HTTPCommon.ATTACHMENT_KEY_REQUEST_ID, requestId);
			if(this.config.isEnableHeaders())
				HTTPCommon.setDefaultHeaders(this.proxy, request);

			String hostname = request.getAuthority();
			if(hostname == null)
				throw new InvalidHTTPMessageException("Missing Host header", true);

			MessageBodyDechunker dechunker = new MessageBodyDechunker(request, (reqdata) -> {
				boolean last = reqdata.length == 0;
				if(this.currentUpstreamServer != null){
					HTTPRequestData hmd = new HTTPRequestData(request, last, reqdata);
					this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_DATA, this.downstreamConnection, hmd, this.currentUpstreamServer);
					// ! reqdata is possibly invalid after this point; data in hmd might have been modified by an event handler
					if(this.currentUpstreamConnection != null)
						transferChunk(request, hmd.getData(), last, this.downstreamConnection, this.currentUpstreamConnection);
					if(last)
						this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_ENDED, this.downstreamConnection, request, this.currentUpstreamServer);
				}
				if(last){
					if(this.currentUpstreamConnection == null && !request.hasResponse())
						throw new IllegalStateException("Non-forwarded request has no response after onHTTPRequestEnded");
					this.endRequest(request);
				}
			});
			request.setAttachment(ATTACHMENT_KEY_DECHUNKER, dechunker);

			this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_PRE_LOG, this.downstreamConnection, request);
			if(!this.config.isDisableDefaultRequestLog())
				logger.info(this.downstreamConnection.getApparentRemoteAddress(), "/", HTTPCommon.shortenRequestId(requestId), " - '", request.requestLine(), "'");

			// init connection to upstream server; breaking here requires a queued response, and will cause simply sending the response after the request ended
			upstream: {
				UpstreamServer userver = this.proxy.getUpstreamServer(hostname, request.getPath());
				if(userver == null){
					logger.debug(this.downstreamConnectionDbgstr, " No upstream server found");
					this.proxy.dispatchEvent(ProxyEvents.INVALID_UPSTREAM_SERVER, this.downstreamConnection, request);
					this.respondError(request, STATUS_NOT_FOUND, HTTPCommon.MSG_NO_SERVER);
					break upstream;
				}

				this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_PRE, this.downstreamConnection, request, userver);
				if(request.hasResponse())
					break upstream;

				AbstractSocketConnection uconn;
				if(userver.getAddress() != null){
					if((uconn = this.upstreamConnections.get(userver)) == null)
						if((uconn = this.createUpstreamConnection(userver)) == null)
							break upstream;
				}else
					uconn = null;

				boolean wasChunked = request.isChunkedTransfer();
				this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST, this.downstreamConnection, request, userver);
				this.currentUpstreamServer = userver;

				postHandleHTTPMessage(wasChunked, request, this.downstreamConnection, uconn);

				if(uconn != null){
					this.writeHTTPMsg(uconn, request, null);

					this.currentUpstreamConnection = uconn;
					if(!uconn.hasConnected())
						uconn.connect(this.config.getUpstreamConnectionTimeout());
				}
			}
		}else if(this.currentRequest.hasAttachment(ATTACHMENT_KEY_UPROTOCOL)){
			if(this.currentUpstreamConnection == null || !this.currentUpstreamConnection.isConnected()){
				logger.warn("Received unknown protocol data but upstream connection is no longer connected");
				this.downstreamConnection.destroy();
				return;
			}
			this.currentUpstreamConnection.write(data);
			return;
		}
		MessageBodyDechunker dechunker = (MessageBodyDechunker) this.currentRequest.getAttachment(ATTACHMENT_KEY_DECHUNKER);
		if(dechunker == null){
			logger.debug(this.downstreamConnectionDbgstr, " Received data after request ended");
			this.downstreamConnection.close();
			return;
		}
		try{
			dechunker.addData(data);
		}catch(InvalidHTTPMessageException e){
			logger.debug(this.downstreamConnectionDbgstr, " Request body format error: ", NetCommon.PRINT_STACK_TRACES ? e : e.toString());
			this.respondError(this.currentRequest, STATUS_BAD_REQUEST, "Malformed request body");
			dechunker.end();
		}
	}

	private void handleRequestTimeout() {
		logger.debug(this.downstreamConnectionDbgstr, " Request timeout");
		try{
			HTTPRequest request = this.currentRequest;
			this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_TIMEOUT, this.downstreamConnection, request);
			if(request == null || !request.hasResponse())
				this.respondError(request, STATUS_REQUEST_TIMEOUT, HTTPCommon.MSG_REQUEST_TIMEOUT);
			this.requestReceiver.reset();
			if(request != null)
				this.endRequest(request);
			if(this.currentUpstreamConnection != null)
				this.currentUpstreamConnection.close();
		}catch(Exception e){
			logger.error(this.downstreamConnectionDbgstr, " Error while handling request timeout: ", e);
			this.downstreamConnection.close();
		}
	}

	private void handleResponseTimeout() {
		logUNetError(this.currentUpstreamConnection.getAttachment(CONNDBG), " Response timeout");
		try{
			HTTPRequest request = this.currentRequest;
			if(request != null){
				this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_TIMEOUT, this.downstreamConnection, this.currentUpstreamConnection, request, this.currentUpstreamServer);
				this.respondUNetError(request, STATUS_GATEWAY_TIMEOUT, HTTPCommon.MSG_UPSTREAM_RESPONSE_TIMEOUT, this.currentUpstreamConnection,
						this.currentUpstreamServer);
			}
			this.currentUpstreamConnection.destroy();
			this.responseReceiver.reset();
		}catch(Exception e){
			this.respondInternalError(e);
			logger.error(this.currentUpstreamConnection.getAttachment(CONNDBG), " Error while handling response timeout: ", e);
		}
	}

	private synchronized void endRequest(HTTPRequest request) {
		synchronized(request){
			request.setAttachment(ATTACHMENT_KEY_DECHUNKER, null);
			HTTPResponseData res;
			if((res = (HTTPResponseData) request.removeAttachment(ATTACHMENT_KEY_PENDING_RESPONSE)) != null){
				this.writeHTTPMsg(this.downstreamConnection, res.getHttpMessage(), res.getData());
				this.currentRequest = null;
			}else if(this.currentUpstreamConnection != null && this.config.getResponseTimeout() > 0){
				long tid = Tasks.timeout(this::handleResponseTimeout, this.config.getResponseTimeout()).daemon().getId();
				request.setAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT, tid);
			}
		}
	}

	private byte[] processResponsePacket(byte[] data, UpstreamServer userver, AbstractSocketConnection uconn) throws IOException {
		assert Thread.holdsLock(this);
		if(this.currentUpstreamConnection != uconn || this.currentRequest == null)
			throw new IOException("Unexpected data");

		final HTTPRequest request = this.currentRequest;
		if(request.hasAttachment(ATTACHMENT_KEY_UPROTOCOL)){
			this.downstreamConnection.write(data);
			return null;
		}

		if(!request.hasResponse()){ // expecting a response
			int offset;
			try{
				offset = this.responseReceiver.receive(data, 0);
			}catch(InvalidHTTPMessageException e){
				this.proxy.dispatchEvent(ProxyEvents.INVALID_HTTP_RESPONSE, this.downstreamConnection, uconn, request, data);
				throw e;
			}
			if(offset < 0)
				return null;

			HTTPResponse response = this.responseReceiver.get(org.omegazero.proxy.http.ProxyHTTPResponse::new);
			this.responseReceiver.reset();

			data = Arrays.copyOfRange(data, offset, data.length);

			if(request.hasAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT))
				Tasks.clear((long) request.getAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT));

			response.setOther(request);
			if(this.config.isEnableHeaders()){
				if(!response.headerExists("date"))
					response.setHeader("Date", HTTPCommon.dateString());
				HTTPCommon.setDefaultHeaders(this.proxy, response);
			}
			request.setOther(response);

			try{
				boolean wasChunked = response.isChunkedTransfer();
				this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE, this.downstreamConnection, uconn, response, userver);
				if(response.getStatus() == STATUS_SWITCHING_PROTOCOLS){
					request.setAttachment(ATTACHMENT_KEY_UPROTOCOL, 1);
					logger.debug(uconn.getAttachment(CONNDBG), " Protocol changed");
					this.writeHTTPMsg(this.downstreamConnection, response, null);
					return data;
				}else if(response.isIntermediateMessage()){
					request.setOther(null); // this is not the final response
					this.writeHTTPMsg(this.downstreamConnection, response, null);
					return data;
				}

				MessageBodyDechunker dechunker = new MessageBodyDechunker(response, (resdata) -> {
					boolean last = resdata.length == 0;
					HTTPResponseData hmd = new HTTPResponseData(response, last, resdata);
					this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_DATA, this.downstreamConnection, uconn, hmd, userver);
					resdata = hmd.getData();
					transferChunk(response, resdata, last, uconn, this.downstreamConnection);
					if(last){
						this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_ENDED, this.downstreamConnection, uconn, response, userver);
						synchronized(this){
							this.currentRequest = null;
						}
						if("close".equals(request.getHeader("connection")) || "close".equals(response.getHeader("connection")))
							this.downstreamConnection.close();
					}
				});
				response.setAttachment(ATTACHMENT_KEY_DECHUNKER, dechunker);

				postHandleHTTPMessage(wasChunked, response, uconn, this.downstreamConnection);

				if(this.writeHTTPMsg(this.downstreamConnection, response, null))
					dechunker.addData(data);
			}catch(Throwable e){
				// reset other to enable respondError in the onError callback to write the 500 response
				request.setOther(null);
				throw e;
			}
		}else{
			HTTPResponse response = request.getOther();
			MessageBodyDechunker dechunker = (MessageBodyDechunker) response.getAttachment(ATTACHMENT_KEY_DECHUNKER);
			dechunker.addData(data);
		}
		return null;
	}

	private AbstractSocketConnection createUpstreamConnection(UpstreamServer userver) {
		assert Thread.holdsLock(this);
		if(!userver.isProtocolSupported("http/1.1")){
			this.respondError(this.currentRequest, STATUS_HTTP_VERSION_NOT_SUPPORTED, HTTPCommon.MSG_PROTO_NOT_SUPPORTED + "HTTP/1");
			return null;
		}

		AbstractSocketConnection uconn;
		try{
			uconn = (AbstractSocketConnection) ProxyUtil.connectUpstreamTCP(this.proxy, this.downstreamConnection, this.downstreamSecurity, userver, HTTP1_ALPN);
		}catch(IOException e){
			this.respondInternalError(e);
			logger.error("Connection failed: ", e);
			return null;
		}

		uconn.setAttachment(CONNDBG, this.proxy.debugStringForConnection(this.downstreamConnection, uconn));
		uconn.setOnConnect(() -> {
			logger.debug(uconn.getAttachment(CONNDBG), " Connected");
			this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION, uconn);
		});
		uconn.setOnTimeout(() -> {
			logUNetError(uconn.getAttachment(CONNDBG), " Connect timeout");
			this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_TIMEOUT, uconn);
			synchronized(this){
				if(this.currentUpstreamConnection == uconn && this.currentRequest != null)
					this.respondUNetError(this.currentRequest, STATUS_GATEWAY_TIMEOUT, HTTPCommon.MSG_UPSTREAM_CONNECT_TIMEOUT, uconn, userver);
			}
		});
		uconn.setOnError((e) -> {
			Exception e2 = null;
			try{
				this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_ERROR, uconn, e);

				synchronized(this){
					if(this.currentUpstreamConnection == uconn && this.currentRequest != null){
						if(e instanceof IOException)
							this.respondUNetError(this.currentRequest, STATUS_BAD_GATEWAY, HTTPCommon.getUpstreamErrorMessage(e), uconn, userver);
						else
							this.respondError(this.currentRequest, STATUS_INTERNAL_SERVER_ERROR, HTTPCommon.MSG_SERVER_ERROR);
					}
				}
			}catch(Exception ue){
				e2 = ue;
			}
			if(e2 != null)
				this.respondInternalError(e2);
			if(e instanceof IOException){
				logUNetError(uconn.getAttachment(CONNDBG), " Error: ", NetCommon.PRINT_STACK_TRACES ? e : e.toString());
				if(e2 != null)
					logger.error(uconn.getAttachment(CONNDBG), " Internal error while handling upstream connection error: ", e2);
			}else{
				if(e2 != null)
					e.addSuppressed(e2);
				logger.error(uconn.getAttachment(CONNDBG), " Internal error: ", e);
			}
		});
		uconn.setOnClose(() -> {
			logger.debug(uconn.getAttachment(CONNDBG), " Disconnected");
			if(this.downstreamConnection.isConnected())
				this.downstreamConnection.setReadBlock(false); // release backpressure
			this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_CLOSED, uconn);

			synchronized(this){
				if(this.currentUpstreamConnection == uconn && !this.downstreamClosed && this.currentRequest != null){
					HTTPResponse response = this.currentRequest.getOther();
					if(response == null){
						// did not receive a response
						logUNetError(uconn.getAttachment(CONNDBG), " Connection closed unexpectedly");
						this.respondUNetError(this.currentRequest, STATUS_BAD_GATEWAY, HTTPCommon.MSG_UPSTREAM_CONNECTION_CLOSED, uconn, userver);
					}else{
						MessageBodyDechunker dechunker = (MessageBodyDechunker) response.getAttachment(ATTACHMENT_KEY_DECHUNKER);
						if(dechunker != null){ // may be null if respond() was used
							if(!dechunker.hasReceivedAllData()){
								logUNetError(uconn.getAttachment(CONNDBG), " Closing downstream connection because upstream connection closed before all data was received");
								this.downstreamConnection.close();
							}else
								dechunker.end();
						}
						// currentRequest may be set to null by dechunker.end()
						if(this.currentRequest != null && this.currentRequest.hasAttachment(ATTACHMENT_KEY_UPROTOCOL))
							this.downstreamConnection.close();
					}
				}
			}

			this.upstreamConnections.remove(userver, uconn);
		});
		uconn.setOnData((d) -> {
			synchronized(this){
				do{
					d = this.processResponsePacket(d, userver, uconn);
				}while(d != null && d.length > 0);
			}
		});
		this.upstreamConnections.put(this.currentUpstreamServer, uconn);
		return uconn;
	}

	private boolean writeHTTPMsg(SocketConnection conn, HTTPMessage msg, byte[] data) {
		if(conn.isConnected() && !conn.isWritable()){
			// the socket should always be writable before sending a HTTP message over it under normal circumstances
			// if this is not checked, it may cause a CWE-400 vulnerability if an attacker continuously causes messages to be generated, but never receiving them,
			// eventually causing the write buffer to use all available resources
			// need to check for isConnected because this may be called before the socket is actually connected (isWritable may return false then)
			logger.warn("Tried to write HTTP message on blocked socket; destroying socket [DoS mitigation]");
			conn.destroy();
			return false;
		}
		conn.write(this.transmitter.generate(msg));
		if(data != null && data.length > 0)
			conn.write(data);
		return true;
	}


	private static void transferChunk(HTTPMessage msg, byte[] data, boolean end, SocketConnection sourceConnection, SocketConnection targetConnection) {
		if(data != null && data.length > 0){
			ProxyUtil.handleBackpressure(targetConnection, sourceConnection);
			if(msg.isChunkedTransfer())
				targetConnection.write(HTTP1Util.toChunk(data));
			else{
				targetConnection.write(data);
			}
		}
		if(end){
			if(msg.isChunkedTransfer())
				targetConnection.write(EMPTY_CHUNK);
		}
	}

	private static void postHandleHTTPMessage(boolean wasChunked, HTTPMessage msg, SocketConnection sourceConnection, SocketConnection targetConnection) {
		if(wasChunked && !msg.isChunkedTransfer()){
			throw new IllegalStateException("Cannot unchunkify a response body");
		}else if(!wasChunked && msg.isChunkedTransfer()){
			msg.deleteHeader("content-length");
			msg.setHeader("transfer-encoding", "chunked");
		}
		if(targetConnection != null)
			ProxyUtil.handleBackpressure(targetConnection, sourceConnection);
	}

	private static void logUNetError(Object... o) {
		if(HTTPCommon.USOCKET_ERROR_DEBUG)
			logger.debug(o);
		else
			logger.warn(o);
	}
}
