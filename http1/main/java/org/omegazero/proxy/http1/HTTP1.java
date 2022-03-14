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
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.net.socket.impl.TLSConnection;
import org.omegazero.proxy.config.HTTPEngineConfig;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.core.ProxyEvents;
import org.omegazero.proxy.http.HTTPCommon;
import org.omegazero.proxy.http.HTTPEngine;
import org.omegazero.proxy.http.HTTPErrdoc;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.ProxyUtil;

import static org.omegazero.http.util.HTTPStatus.*;

public class HTTP1 implements HTTPEngine {

	private static final Logger logger = LoggerUtil.createLogger();

	private static final String[] HTTP1_ALPN = new String[] { "http/1.1" };
	private static final byte[] EMPTY_CHUNK = new byte[] { '0', 0xd, 0xa, 0xd, 0xa };

	private static final String ATTACHMENT_KEY_DECHUNKER = "engine_dechunker";
	private static final String ATTACHMENT_KEY_PENDING_RESPONSE = "engine_pendingresponse";
	private static final String ATTACHMENT_KEY_UPROTOCOL = "engine_otherProtocol";


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
	private UpstreamServer currentUpstreamServer;
	private SocketConnection currentUpstreamConnection;
	private Map<UpstreamServer, SocketConnection> upstreamConnections = new java.util.concurrent.ConcurrentHashMap<>();

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
				logger.error("Error while processing packet: ", e);
				this.respondError(this.currentRequest, STATUS_INTERNAL_SERVER_ERROR, HTTPCommon.MSG_SERVER_ERROR);
				this.downstreamConnection.close();
			}else
				throw e;
		}
	}

	@Override
	public synchronized void close() {
		this.downstreamClosed = true;
		for(SocketConnection uconn : this.upstreamConnections.values())
			uconn.close();
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
			throw new IllegalStateException("Connection is no longer active");

		HTTPResponse response = responsedata.getHttpMessage();
		byte[] data = responsedata.getData();
		if(!HTTPCommon.setRequestResponse(request, response))
			return;
		response.deleteHeader("transfer-encoding");
		data = HTTPCommon.prepareHTTPResponse(request, response, data);
		if(request != null){
			synchronized(request){
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
		if(request != null && request.hasResponse())
			return;
		this.respondEx(request, status, data, headers);
	}

	@Override
	public void respondError(HTTPRequest request, int status, String title, String message, String... headers) {
		if(request != null && request.hasResponse())
			return;
		String accept = request != null ? request.getHeader("accept") : null;
		HTTPErrdoc errdoc = this.proxy.getErrdocForAccept(accept);
		byte[] errdocData = errdoc
				.generate(status, title, message, request != null ? request.getHeader("x-request-id") : null, this.downstreamConnection.getApparentRemoteAddress().toString())
				.getBytes();
		this.respondEx(request, status, errdocData, headers, "content-type", errdoc.getMimeType());
	}


	private void respondUNetError(HTTPRequest request, int status, String message, SocketConnection uconn, UpstreamServer userver) {
		if(request.hasResponse())
			return;
		this.proxy.dispatchEvent(ProxyEvents.HTTP_FORWARD_FAILED, this.downstreamConnection, uconn, request, userver);
		if(!request.hasResponse())
			this.respondError(request, status, message);
	}


	private void respondEx(HTTPRequest request, int status, byte[] data, String[] h1, String... hEx) {
		logger.debug(this.downstreamConnectionDbgstr, " Responding with status ", status);
		HTTPResponse response = new HTTPResponse(status, "HTTP/1.1", null);
		for(int i = 0; i + 1 < hEx.length; i += 2){
			response.setHeader(hEx[i], hEx[i + 1]);
		}
		for(int i = 0; i + 1 < h1.length; i += 2){
			response.setHeader(h1[i], h1[i + 1]);
		}

		if(!response.headerExists("date"))
			response.setHeader("date", HTTPCommon.dateString());
		if(!response.headerExists("connection"))
			response.setHeader("connection", "close");
		response.setHeader("server", this.proxy.getInstanceName());
		response.setHeader("x-proxy-engine", this.getClass().getSimpleName());
		if(request != null)
			response.setHeader("x-request-id", (String) request.getAttachment(HTTPCommon.ATTACHMENT_KEY_REQUEST_ID));

		this.respond(request, new HTTPResponseData(response, data));
	}


	private void processPacket(byte[] data) throws InvalidHTTPMessageException {
		assert Thread.holdsLock(this);
		if(this.currentRequest == null){ // expecting a new request
			int offset;
			try{
				offset = this.requestReceiver.receive(data, 0);
			}catch(InvalidHTTPMessageException e){
				this.proxy.dispatchEvent(ProxyEvents.INVALID_HTTP_REQUEST, this.downstreamConnection, data);
				throw e;
			}
			if(offset < 0)
				return;

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

			this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_PRE_LOG, this.downstreamConnection, request);
			if(!this.config.isDisableDefaultRequestLog())
				logger.info(this.downstreamConnection.getApparentRemoteAddress(), "/", HTTPCommon.shortenRequestId(requestId), " - '", request.requestLine(), "'");

			String hostname = request.getAuthority();
			if(hostname == null)
				throw new InvalidHTTPMessageException("Missing Host header", true);

			MessageBodyDechunker dechunker = new MessageBodyDechunker(request, (reqdata) -> {
				boolean last = reqdata.length == 0;
				if(this.currentUpstreamConnection != null){
					HTTPRequestData hmd = new HTTPRequestData(request, last, reqdata);
					this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_DATA, this.downstreamConnection, hmd, this.currentUpstreamServer);
					reqdata = hmd.getData(); // data in hmd might have been modified by an event handler
					transferChunk(request, reqdata, last, this.downstreamConnection, this.currentUpstreamConnection);
					if(last)
						this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_ENDED, this.downstreamConnection, request, this.currentUpstreamServer);
				}
				if(last){
					synchronized(request){
						request.setAttachment(ATTACHMENT_KEY_DECHUNKER, null);
						HTTPResponseData res;
						if((res = (HTTPResponseData) request.removeAttachment(ATTACHMENT_KEY_PENDING_RESPONSE)) != null){
							this.writeHTTPMsg(this.downstreamConnection, res.getHttpMessage(), res.getData());
							this.currentRequest = null;
						}
					}
				}
			});
			request.setAttachment(ATTACHMENT_KEY_DECHUNKER, dechunker);

			// init connection to upstream server; breaking here requires a queued response, and will cause simply sending the response after the request ended
			upstream: {
				this.currentUpstreamServer = this.proxy.getUpstreamServer(hostname, request.getPath());
				if(this.currentUpstreamServer == null){
					logger.debug(this.downstreamConnectionDbgstr, " No upstream server found");
					this.proxy.dispatchEvent(ProxyEvents.INVALID_UPSTREAM_SERVER, this.downstreamConnection, request);
					this.respondError(request, STATUS_NOT_FOUND, HTTPCommon.MSG_NO_SERVER);
					break upstream;
				}

				this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_PRE, this.downstreamConnection, request, this.currentUpstreamServer);
				if(request.hasResponse())
					break upstream;

				SocketConnection uconn;
				if((uconn = this.upstreamConnections.get(this.currentUpstreamServer)) == null){
					if((uconn = this.createUpstreamConnection(this.currentUpstreamServer)) == null)
						break upstream;
				}

				boolean wasChunked = request.isChunkedTransfer();
				this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST, this.downstreamConnection, request, this.currentUpstreamServer);

				postHandleHTTPMessage(wasChunked, request, this.downstreamConnection, uconn);

				this.writeHTTPMsg(uconn, request, null);

				this.currentUpstreamConnection = uconn;
				if(!uconn.hasConnected())
					uconn.connect(this.config.getUpstreamConnectionTimeout());
			}
			assert this.currentUpstreamConnection != null || request.hasResponse() : "Non-forwarded request has no response";
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

	private byte[] processResponsePacket(byte[] data, UpstreamServer userver, SocketConnection uconn) throws IOException {
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
					logger.debug(uconn.getAttachment(), " Protocol changed");
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
						this.currentRequest = null;
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

	private SocketConnection createUpstreamConnection(UpstreamServer userver) {
		assert Thread.holdsLock(this);
		if(!userver.isProtocolSupported("http/1.1")){
			this.respondError(this.currentRequest, STATUS_HTTP_VERSION_NOT_SUPPORTED, HTTPCommon.MSG_PROTO_NOT_SUPPORTED + "HTTP/1");
			return null;
		}

		SocketConnection uconn;
		try{
			uconn = ProxyUtil.connectUpstreamTCP(this.proxy, this.downstreamSecurity, userver, HTTP1_ALPN);
		}catch(IOException e){
			logger.error("Connection failed: ", e);
			this.respondError(this.currentRequest, STATUS_INTERNAL_SERVER_ERROR, HTTPCommon.MSG_SERVER_ERROR);
			return null;
		}

		uconn.setAttachment(this.proxy.debugStringForConnection(this.downstreamConnection, uconn));
		uconn.setOnConnect(() -> {
			logger.debug(uconn.getAttachment(), " Connected");
			this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION, uconn);
		});
		uconn.setOnTimeout(() -> {
			logUNetError(uconn.getAttachment(), " Connect timeout");
			this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_TIMEOUT, uconn);
			if(this.currentUpstreamConnection == uconn && this.currentRequest != null)
				this.respondUNetError(this.currentRequest, STATUS_GATEWAY_TIMEOUT, HTTPCommon.MSG_UPSTREAM_CONNECT_TIMEOUT, uconn, userver);
		});
		uconn.setOnError((e) -> {
			if(e instanceof IOException)
				logUNetError(uconn.getAttachment(), " Error: ", NetCommon.PRINT_STACK_TRACES ? e : e.toString());
			else
				logger.error(uconn.getAttachment(), " Internal error: ", e);

			try{
				this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_ERROR, uconn, e);

				if(this.currentUpstreamConnection == uconn && this.currentRequest != null){
					if(e instanceof IOException)
						this.respondUNetError(this.currentRequest, STATUS_BAD_GATEWAY, HTTPCommon.getUpstreamErrorMessage(e), uconn, userver);
					else
						this.respondError(this.currentRequest, STATUS_INTERNAL_SERVER_ERROR, HTTPCommon.MSG_SERVER_ERROR);
				}
			}catch(Exception ue){
				logger.error("Internal error while handling upstream connection error: ", ue);
			}
		});
		uconn.setOnClose(() -> {
			logger.debug(uconn.getAttachment(), " Disconnected");
			if(this.downstreamConnection.isConnected())
				this.downstreamConnection.setReadBlock(false); // release backpressure
			this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_CLOSED, uconn);

			if(this.currentUpstreamConnection == uconn && !this.downstreamClosed && this.currentRequest != null){
				HTTPResponse response = this.currentRequest.getOther();
				if(response == null){
					// did not receive a response
					logUNetError(uconn.getAttachment(), " Connection closed unexpectedly");
					this.respondUNetError(this.currentRequest, STATUS_BAD_GATEWAY, HTTPCommon.MSG_UPSTREAM_CONNECTION_CLOSED, uconn, userver);
				}else{
					MessageBodyDechunker dechunker = (MessageBodyDechunker) response.getAttachment(ATTACHMENT_KEY_DECHUNKER);
					if(dechunker != null){ // may be null if respond() was used
						if(!dechunker.hasReceivedAllData()){
							logUNetError(uconn.getAttachment(), " Closing downstream connection because upstream connection closed before all data was received");
							this.downstreamConnection.close();
						}else
							dechunker.end();
					}
					if(this.currentRequest.hasAttachment(ATTACHMENT_KEY_UPROTOCOL))
						this.downstreamConnection.close();
				}
			}

			this.upstreamConnections.remove(userver, uconn);
		});
		uconn.setOnData((d) -> {
			do{
				d = this.processResponsePacket(d, userver, uconn);
			}while(d != null && d.length > 0);
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
		if(wasChunked && !msg.isChunkedTransfer())
			throw new IllegalStateException("Cannot unchunkify a response body");
		else if(!wasChunked && msg.isChunkedTransfer()){
			msg.deleteHeader("content-length");
			msg.setHeader("transfer-encoding", "chunked");
		}
		ProxyUtil.handleBackpressure(targetConnection, sourceConnection);
	}

	private static void logUNetError(Object... o) {
		if(HTTPCommon.USOCKET_ERROR_DEBUG)
			logger.debug(o);
		else
			logger.warn(o);
	}
}
