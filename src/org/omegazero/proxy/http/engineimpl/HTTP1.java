/*
 * Copyright (C) 2021 omegazero.org
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Covered Software is provided under this License on an "as is" basis, without warranty of any kind,
 * either expressed, implied, or statutory, including, without limitation, warranties that the Covered Software
 * is free of defects, merchantable, fit for a particular purpose or non-infringing.
 * The entire risk as to the quality and performance of the Covered Software is with You.
 */
package org.omegazero.proxy.http.engineimpl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.common.util.ArrayUtil;
import org.omegazero.net.client.NetClientManager;
import org.omegazero.net.client.PlainTCPClientManager;
import org.omegazero.net.client.TLSClientManager;
import org.omegazero.net.client.params.ConnectionParameters;
import org.omegazero.net.client.params.TLSConnectionParameters;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.net.socket.impl.TLSConnection;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.core.ProxyEvents;
import org.omegazero.proxy.http.HTTPCommon;
import org.omegazero.proxy.http.HTTPEngine;
import org.omegazero.proxy.http.HTTPErrdoc;
import org.omegazero.proxy.http.HTTPMessage;
import org.omegazero.proxy.http.HTTPMessageData;
import org.omegazero.proxy.http.InvalidHTTPMessageException;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.ProxyUtil;

public class HTTP1 implements HTTPEngine {

	private static final Logger logger = LoggerUtil.createLogger();

	private static final byte[] HTTP1_HEADER_END = new byte[] { 0x0d, 0x0a, 0x0d, 0x0a };
	private static final String[] HTTP1_ALPN = new String[] { "http/1.1" };

	private static final byte[] EOL = new byte[] { 0xd, 0xa };
	private static final byte[] EMPTY_CHUNK = new byte[] { '0', 0xd, 0xa, 0xd, 0xa };

	private static final Pattern PATTERN_REQUEST_METHOD = Pattern.compile("[A-Z]{2,10}");
	private static final Pattern PATTERN_REPONSE_STATUS = Pattern.compile("[0-9]{2,4}");
	private static final Pattern PATTERN_HTTP_VERSION = Pattern.compile("HTTP/1\\.[01]");


	private final SocketConnection downstreamConnection;
	private final Proxy proxy;

	private final String downstreamConnectionDbgstr;
	private final boolean downstreamSecurity;

	private final boolean disableDefaultRequestLog;

	private boolean downstreamClosed;

	private HTTPMessage lastRequest;
	private UpstreamServer lastUpstreamServer;
	private Map<UpstreamServer, SocketConnection> upstreamConnections = new java.util.concurrent.ConcurrentHashMap<>();

	public HTTP1(SocketConnection downstreamConnection, Proxy proxy) {
		this.downstreamConnection = Objects.requireNonNull(downstreamConnection);
		this.proxy = Objects.requireNonNull(proxy);

		this.downstreamConnectionDbgstr = this.proxy.debugStringForConnection(this.downstreamConnection, null);
		this.downstreamSecurity = this.downstreamConnection instanceof TLSConnection;

		this.disableDefaultRequestLog = this.proxy.getConfig().isDisableDefaultRequestLog();
	}


	@Override
	public synchronized void processData(byte[] data) {
		try{
			this.processPacket(data);
		}catch(Exception e){
			if(this.lastRequest != null){
				logger.error("Error while processing packet: ", e);
				this.respondError(this.lastRequest, HTTPCommon.STATUS_INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error has occurred");
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
	public void respond(HTTPMessage request, HTTPMessageData responsedata) {
		HTTPMessage response = responsedata.getHttpMessage().clone();
		byte[] data = responsedata.getData();
		if(!setRequestResponse(request, response))
			return;
		data = fixHTTPResponse(request, response, data);
		HTTP1.writeHTTPMsg(this.downstreamConnection, response, data);
	}

	@Override
	public void respond(HTTPMessage request, int status, byte[] data, String... headers) {
		this.respondEx(request, status, data, headers);
	}

	@Override
	public void respondError(HTTPMessage request, int status, String title, String message, String... headers) {
		if(request != null && request.getCorrespondingMessage() != null)
			return;
		String accept = request != null ? request.getHeader("accept") : null;
		String[] acceptParts = accept != null ? accept.split(",") : new String[0];
		HTTPErrdoc errdoc = null;
		for(String ap : acceptParts){
			int pe = ap.indexOf(';');
			if(pe >= 0)
				ap = ap.substring(0, pe);
			errdoc = this.proxy.getErrdoc(ap.trim());
			if(errdoc != null)
				break;
		}
		if(errdoc == null)
			errdoc = this.proxy.getDefaultErrdoc();
		byte[] errdocData = errdoc
				.generate(status, title, message, request != null ? request.getHeader("x-request-id") : null, this.downstreamConnection.getApparentRemoteAddress().toString())
				.getBytes();
		this.respondEx(request, status, errdocData, headers, "content-type", errdoc.getMimeType());
	}


	private synchronized void respondEx(HTTPMessage request, int status, byte[] data, String[] h1, String... hEx) {
		if(request != null && request.getCorrespondingMessage() != null) // received response already
			return;
		logger.debug(this.downstreamConnectionDbgstr, " Responding with status ", status);
		HTTPMessage response = new HTTPMessage(status, "HTTP/1.1");
		for(int i = 0; i + 1 < hEx.length; i += 2){
			response.setHeader(hEx[i], hEx[i + 1]);
		}
		for(int i = 0; i + 1 < h1.length; i += 2){
			response.setHeader(h1[i], h1[i + 1]);
		}

		data = fixHTTPResponse(request, response, data);

		if(!response.headerExists("date"))
			response.setHeader("date", HTTPCommon.dateString());
		if(!response.headerExists("connection"))
			response.setHeader("connection", "close");
		response.setHeader("server", this.proxy.getInstanceName());
		response.setHeader("x-proxy-engine", this.getClass().getSimpleName());
		if(request != null)
			response.setHeader("x-request-id", request.getRequestId());
		if(!setRequestResponse(request, response))
			return;
		HTTP1.writeHTTPMsg(this.downstreamConnection, response, data);
	}


	// called in synchronized context
	private void processPacket(byte[] data) {
		if(this.forwardUnknownProtocolPacket(data))
			return;

		HTTPMessageData requestdata = this.parseHTTPRequest(data);
		HTTPMessage request = this.lastRequest;
		if(requestdata != null){
			request = requestdata.getHttpMessage();
			this.lastRequest = request;
			request.setRequestId(HTTPCommon.requestId(this.downstreamConnection));
			if(this.proxy.enableHeaders()){
				HTTPCommon.setDefaultHeaders(this.proxy, request);
			}

			this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_PRE_LOG, this.downstreamConnection, request);
			if(!this.disableDefaultRequestLog)
				logger.info(this.downstreamConnection.getApparentRemoteAddress(), "/", HTTPCommon.shortenRequestId(request.getRequestId()), " - '", request.requestLine(),
						"'");
			if(this.hasReceivedResponse())
				return;

			String hostname = request.getAuthority();
			if(hostname == null){
				logger.info(this.downstreamConnectionDbgstr, " No Host header");
				this.respondError(request, HTTPCommon.STATUS_BAD_REQUEST, "Bad Request", "Missing Host header");
				return;
			}

			this.lastUpstreamServer = this.proxy.getUpstreamServer(hostname, request.getPath());
			if(this.lastUpstreamServer == null){
				logger.info(this.downstreamConnectionDbgstr, " No upstream server found");
				this.proxy.dispatchEvent(ProxyEvents.INVALID_UPSTREAM_SERVER, this.downstreamConnection, request);
				this.respondError(request, HTTPCommon.STATUS_NOT_FOUND, "Not Found", "No appropriate upstream server was found for this request");
				return;
			}

			this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_PRE, this.downstreamConnection, request, this.lastUpstreamServer);
			if(this.hasReceivedResponse())
				return;
		}else if(this.hasReceivedResponse()) // this packet is not a new request, but remaining body data from the previous request; sent response for that request already
			return;

		UpstreamServer userver = this.lastUpstreamServer;
		if(userver == null){ // here: implies request is null (invalid)
			this.proxy.dispatchEvent(ProxyEvents.INVALID_HTTP_REQUEST, this.downstreamConnection, data);
			if(this.hasReceivedResponse())
				return;
			logger.info(this.downstreamConnectionDbgstr, " Invalid Request");
			this.respondError(null, HTTPCommon.STATUS_BAD_REQUEST, "Bad Request", "The proxy server did not understand the request");
			return;
		}
		SocketConnection uconn = null;
		if((uconn = this.upstreamConnections.get(userver)) == null){
			if((uconn = this.connectUpstream(userver)) == null)
				return;
		}
		if(requestdata != null){
			boolean wasChunked = request.isChunkedTransfer();
			this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST, this.downstreamConnection, request, userver);
			try{
				MessageBodyDechunker dc = this.handleHTTPMessage(wasChunked, request, HTTP1.this.downstreamConnection, uconn, (hmd) -> {
					HTTP1.this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_DATA, this.downstreamConnection, hmd, userver);
				}, (msg) -> {
					HTTP1.this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_ENDED, this.downstreamConnection, msg, userver);
				});
				if(HTTP1.writeHTTPMsg(uconn, request, null))
					dc.addData(requestdata.getData());
			}catch(IOException | UnsupportedOperationException e){
				logger.warn("Error while processing request body chunk: ", e.toString());
				this.respondError(null, HTTPCommon.STATUS_BAD_REQUEST, "Bad Request", "Malformed request body");
			}
		}else{
			MessageBodyDechunker dechunker = (MessageBodyDechunker) request.getAttachment("engine_dechunker");
			try{
				dechunker.addData(data);
			}catch(IOException e){
				logger.warn("Error while processing request body chunk: ", e.toString());
				this.downstreamConnection.close();
			}
		}
	}

	private byte[] processResponsePacket(byte[] data, UpstreamServer userver, SocketConnection uconn) throws IOException {
		if(!userver.equals(HTTP1.this.lastUpstreamServer)){
			logger.warn(uconn.getAttachment(), " Received unexpected data");
			return null;
		}

		HTTPMessage req = HTTP1.this.lastRequest;
		if(req.getAttachment("engine_otherProtocol") != null){
			HTTP1.this.downstreamConnection.write(data);
			return null;
		}

		HTTPMessageData responsedata = HTTP1.this.parseHTTPResponse(data);
		final HTTPMessage response;
		if(responsedata != null){
			response = responsedata.getHttpMessage();
			response.setRequestId(req.getRequestId());
			if(HTTP1.this.proxy.enableHeaders()){
				if(!response.headerExists("date"))
					response.setHeader("Date", HTTPCommon.dateString());
				HTTPCommon.setDefaultHeaders(HTTP1.this.proxy, response);
			}
			response.setCorrespondingMessage(req);
			req.setCorrespondingMessage(response);

			try{
				boolean wasChunked = response.isChunkedTransfer();
				HTTP1.this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE, HTTP1.this.downstreamConnection, uconn, response, userver);
				if(response.getStatus() == HTTPCommon.STATUS_SWITCHING_PROTOCOLS){
					req.setAttachment("engine_otherProtocol", 1);
					HTTP1.writeHTTPMsg(HTTP1.this.downstreamConnection, response, responsedata.getData());
					return responsedata.getData();
				}else if(response.isIntermediateMessage()){
					req.setCorrespondingMessage(null); // this is not the final response
					return responsedata.getData();
				}else{
					MessageBodyDechunker dc = this.handleHTTPMessage(wasChunked, response, uconn, HTTP1.this.downstreamConnection, (hmd) -> {
						HTTP1.this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_DATA, this.downstreamConnection, uconn, hmd, userver);
					}, (msg) -> {
						HTTP1.this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_ENDED, this.downstreamConnection, uconn, msg, userver);
						if("close".equals(req.getHeader("connection")) || "close".equals(msg.getHeader("connection")))
							HTTP1.this.downstreamConnection.close();
					});
					if(HTTP1.writeHTTPMsg(HTTP1.this.downstreamConnection, response, null))
						dc.addData(responsedata.getData());
				}
			}catch(Exception e){
				// reset setCorrespondingMessage to enable respondError in the onError callback to write the 500 response
				req.setCorrespondingMessage(null);
				throw e;
			}
		}else{
			response = req.getCorrespondingMessage();
			if(response != null){
				MessageBodyDechunker dechunker = (MessageBodyDechunker) response.getAttachment("engine_dechunker");
				dechunker.addData(data);
			}else{
				HTTP1.this.proxy.dispatchEvent(ProxyEvents.INVALID_HTTP_RESPONSE, HTTP1.this.downstreamConnection, uconn, req, data);
				if(HTTP1.this.hasReceivedResponse())
					return null;
				logger.warn(uconn.getAttachment(), " Invalid response");
				throw new InvalidHTTPMessageException();
			}
		}
		return null;
	}

	// called in synchronized context
	private SocketConnection connectUpstream(UpstreamServer userver) {
		if(!this.proxy.dispatchBooleanEvent(ProxyEvents.UPSTREAM_CONNECTION_PERMITTED, true, this.lastRequest, userver)){
			logger.info(this.downstreamConnectionDbgstr, " Connection to ", userver, " blocked");
			this.respondError(this.lastRequest, HTTPCommon.STATUS_FORBIDDEN, "Forbidden", "You are not permitted to access this resource");
			return null;
		}
		Class<? extends NetClientManager> type;
		ConnectionParameters params;
		if((this.downstreamSecurity || userver.getPlainPort() <= 0) && userver.getSecurePort() > 0){
			type = TLSClientManager.class;
			params = new TLSConnectionParameters(new InetSocketAddress(userver.getAddress(), userver.getSecurePort()));
			((TLSConnectionParameters) params).setAlpnNames(HTTP1_ALPN);
			((TLSConnectionParameters) params).setSniOptions(new String[] { userver.getAddress().getHostName() });
		}else if(userver.getPlainPort() > 0){
			type = PlainTCPClientManager.class;
			params = new ConnectionParameters(new InetSocketAddress(userver.getAddress(), userver.getPlainPort()));
		}else
			throw new RuntimeException("Upstream server " + userver.getAddress() + " neither has a plain nor a secure port set");

		SocketConnection uconn;
		try{
			uconn = this.proxy.connection(type, params);
		}catch(IOException e){
			logger.error("Connection failed: ", e);
			this.respondError(this.lastRequest, HTTPCommon.STATUS_INTERNAL_SERVER_ERROR, "Internal Server Error", "Upstream connection creation failed");
			return null;
		}

		uconn.setAttachment(this.proxy.debugStringForConnection(this.downstreamConnection, uconn));
		uconn.setOnConnect(() -> {
			logger.debug(uconn.getAttachment(), " Connected");
			HTTP1.this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION, uconn);
		});
		uconn.setOnTimeout(() -> {
			logger.error(uconn.getAttachment(), " Connect timed out");
			HTTP1.this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_TIMEOUT, uconn);
			if(userver.equals(HTTP1.this.lastUpstreamServer))
				this.respondError(HTTP1.this.lastRequest, HTTPCommon.STATUS_GATEWAY_TIMEOUT, "Gateway Timeout", "Connection to the upstream server timed out");
		});
		uconn.setOnError((e) -> {
			// error in connection to upstream server is log level error instead of warn (as for downstream connections) because they usually indicate a problem with the
			// upstream server, which is more severe than a client connection getting RSTed
			if(e instanceof IOException)
				logger.error(uconn.getAttachment(), " Error: ", e.toString());
			else
				logger.error(uconn.getAttachment(), " Internal error: ", e);
			HTTP1.this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_ERROR, uconn, e);

			if(userver.equals(HTTP1.this.lastUpstreamServer)){
				if(e instanceof IOException)
					this.respondError(HTTP1.this.lastRequest, HTTPCommon.STATUS_BAD_GATEWAY, "Bad Gateway", HTTPCommon.getUpstreamErrorMessage(e));
				else
					this.respondError(HTTP1.this.lastRequest, HTTPCommon.STATUS_INTERNAL_SERVER_ERROR, "Internal Server Error",
							"An internal error occurred in the connection to the upstream server");
			}
		});
		uconn.setOnClose(() -> {
			logger.debug(uconn.getAttachment(), " Disconnected");
			if(HTTP1.this.downstreamConnection.isConnected())
				HTTP1.this.downstreamConnection.setReadBlock(false); // release backpressure
			HTTP1.this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_CLOSED, uconn);

			if(userver.equals(HTTP1.this.lastUpstreamServer) && !HTTP1.this.downstreamClosed){
				HTTP1.this.lastUpstreamServer = null;
				HTTPMessage response = HTTP1.this.lastRequest.getCorrespondingMessage();
				if(response == null){
					// did not receive a response
					logger.error(uconn.getAttachment(), " Connection closed unexpectedly");
					this.respondError(HTTP1.this.lastRequest, HTTPCommon.STATUS_BAD_GATEWAY, "Bad Gateway", "Connection to the upstream server closed unexpectedly");
				}else{
					MessageBodyDechunker dechunker = (MessageBodyDechunker) response.getAttachment("engine_dechunker");
					if(dechunker != null){ // may be null if respond() was used
						if(!dechunker.hasReceivedAllData()){
							logger.warn("Closing downstream connection because upstream connection closed before all data was received");
							HTTP1.this.downstreamConnection.close();
						}else
							dechunker.end();
					}
					if(HTTP1.this.lastRequest.getAttachment("engine_otherProtocol") != null)
						HTTP1.this.downstreamConnection.close();
				}
			}

			HTTP1.this.upstreamConnections.remove(userver, uconn);
		});
		uconn.setOnData((d) -> {
			do{
				d = HTTP1.this.processResponsePacket(d, userver, uconn);
			}while(d != null && d.length > 0);
		});
		uconn.connect(this.proxy.getUpstreamConnectionTimeout());
		this.upstreamConnections.put(userver, uconn);
		return uconn;
	}

	private boolean forwardUnknownProtocolPacket(byte[] data) {
		HTTPMessage request = this.lastRequest;
		if(request == null || request.getAttachment("engine_otherProtocol") == null || this.lastUpstreamServer == null)
			return false;
		SocketConnection uconn = this.upstreamConnections.get(this.lastUpstreamServer);
		if(uconn == null)
			return false;
		uconn.write(data);
		return true;
	}

	private MessageBodyDechunker handleHTTPMessage(boolean wasChunked, HTTPMessage msg, SocketConnection sourceConnection, SocketConnection targetConnection,
			Consumer<HTTPMessageData> onMsgData, Consumer<HTTPMessage> onFinished) throws IOException {
		MessageBodyDechunker dechunker = new MessageBodyDechunker(msg, (data) -> {
			boolean last = data.length == 0;
			HTTPMessageData hmd = new HTTPMessageData(msg, last, data);
			onMsgData.accept(hmd);
			data = hmd.getData();
			if(data != null && data.length > 0){
				ProxyUtil.handleBackpressure(targetConnection, sourceConnection);
				if(msg.isChunkedTransfer())
					targetConnection.write(toChunk(data));
				else{
					targetConnection.write(data);
				}
			}
			if(last){
				if(msg.isChunkedTransfer())
					targetConnection.write(EMPTY_CHUNK);
				onFinished.accept(msg);
			}
		});
		msg.setAttachment("engine_dechunker", dechunker);
		if(wasChunked && !msg.isChunkedTransfer())
			throw new IllegalStateException("Cannot unchunkify a response body");
		else if(!wasChunked && msg.isChunkedTransfer()){
			msg.deleteHeader("content-length");
			msg.setHeader("transfer-encoding", "chunked");
		}
		msg.lock();
		ProxyUtil.handleBackpressure(targetConnection, sourceConnection);
		return dechunker;
	}


	private boolean hasReceivedResponse() {
		return this.lastRequest != null && this.lastRequest.getCorrespondingMessage() != null;
	}


	private HTTPMessageData parseHTTPRequest(byte[] data) {
		if(data[0] < 'A' || data[0] > 'Z')
			return null;

		// the full header must be sent at once (this was never a problem for real clients)
		// because the read buffer is 8192 bytes large, this also means the header size is limited to 8KiB (same applies to responses)
		int headerEnd = ArrayUtil.byteArrayIndexOf(data, HTTP1_HEADER_END);
		if(headerEnd < 0)
			return null;

		String headerData = new String(data, 0, headerEnd);
		int startLineEnd = headerData.indexOf("\r\n");
		if(startLineEnd < 0)
			return null;
		String[] startLine = headerData.substring(0, startLineEnd).split(" ");
		if(!(startLine.length == 3 && PATTERN_REQUEST_METHOD.matcher(startLine[0]).matches() && PATTERN_HTTP_VERSION.matcher(startLine[2]).matches()))
			return null;
		String requestURI = startLine[1];
		String host = null;
		if(requestURI.charAt(0) != '/' && !requestURI.equals("*")){
			// assuming absolute URI with net_path and abs_path
			int authStart = requestURI.indexOf("://");
			if(authStart < 0)
				return null;
			authStart += 3;
			int pathStart = requestURI.indexOf('/', authStart);
			if(pathStart < 0)
				return null;
			host = requestURI.substring(authStart, pathStart);
			requestURI = requestURI.substring(pathStart);
		}

		// make sure the request uri only contains printable ASCII characters
		for(int i = 0; i < requestURI.length(); i++){
			char c = requestURI.charAt(i);
			if(c <= 32 || c >= 127)
				return null;
		}

		String[] headerLines = headerData.substring(startLineEnd + 2).split("\r\n");
		Map<String, String> headers = new java.util.HashMap<>(headerLines.length);
		for(String headerLine : headerLines){
			int sep = headerLine.indexOf(':');
			if(sep < 0)
				return null;
			headers.put(headerLine.substring(0, sep).trim().toLowerCase(), headerLine.substring(sep + 1).trim());
		}

		if(host == null){
			host = headers.get("host");
		}

		HTTPMessage msg = new HTTPMessage(startLine[0], this.downstreamSecurity ? "https" : "http", host, requestURI, startLine[2], headers);

		return this.parseHTTPCommon(msg, data, headerEnd);
	}

	private HTTPMessageData parseHTTPResponse(byte[] data) {
		if(data[0] != 'H')
			return null;

		int headerEnd = ArrayUtil.byteArrayIndexOf(data, HTTP1_HEADER_END);
		if(headerEnd < 0)
			return null;

		String headerData = new String(data, 0, headerEnd);
		int startLineEnd = headerData.indexOf("\r\n");
		if(startLineEnd < 0)
			startLineEnd = headerEnd;
		String[] startLine = headerData.substring(0, startLineEnd).split(" ");
		if(!(startLine.length >= 2 && PATTERN_HTTP_VERSION.matcher(startLine[0]).matches() && PATTERN_REPONSE_STATUS.matcher(startLine[1]).matches()))
			return null;

		HTTPMessage msg = new HTTPMessage(Integer.parseInt(startLine[1]), startLine[0]);
		String[] headerLines = startLineEnd + 2 < headerData.length() ? headerData.substring(startLineEnd + 2).split("\r\n") : new String[0];
		for(String headerLine : headerLines){
			int sep = headerLine.indexOf(':');
			if(sep < 0)
				return null;
			msg.setHeader(headerLine.substring(0, sep).trim().toLowerCase(), headerLine.substring(sep + 1).trim());
		}

		return this.parseHTTPCommon(msg, data, headerEnd);
	}

	private HTTPMessageData parseHTTPCommon(HTTPMessage msg, byte[] data, int headerEnd) {
		msg.setEngine(this);
		msg.setSize(headerEnd);

		msg.setChunkedTransfer("chunked".equals(msg.getHeader("transfer-encoding")));

		int edataStart = headerEnd + HTTP1_HEADER_END.length;
		int edataLen = data.length - edataStart;
		byte[] edata = new byte[edataLen];
		System.arraycopy(data, edataStart, edata, 0, edataLen);
		return new HTTPMessageData(msg, edata);
	}


	private static boolean setRequestResponse(HTTPMessage request, HTTPMessage response) {
		if(request != null){
			synchronized(request){
				if(request.getCorrespondingMessage() != null) // received response already
					return false;
				request.setCorrespondingMessage(response);
			}
		}
		return true;
	}

	private static byte[] fixHTTPResponse(HTTPMessage request, HTTPMessage response, byte[] data) {
		response.deleteHeader("transfer-encoding");
		if(MessageBodyDechunker.hasResponseBody(response.getStatus())){
			if(request != null && request.getMethod().equals("HEAD")){
				if(data.length > 0)
					data = new byte[0];
			}else
				response.setHeader("content-length", String.valueOf(data.length));
		}else if(data.length > 0)
			throw new IllegalStateException("Response with status " + response.getStatus() + " must not have a response body");
		else
			response.deleteHeader("content-length");
		return data;
	}

	private static byte[] toChunk(byte[] data) {
		byte[] hexlen = Integer.toString(data.length, 16).getBytes();
		int chunkFrameSize = data.length + hexlen.length + EOL.length * 2;
		byte[] chunk = new byte[chunkFrameSize];
		int i = 0;
		System.arraycopy(hexlen, 0, chunk, i, hexlen.length);
		i += hexlen.length;
		System.arraycopy(EOL, 0, chunk, i, EOL.length);
		i += EOL.length;
		System.arraycopy(data, 0, chunk, i, data.length);
		i += data.length;
		System.arraycopy(EOL, 0, chunk, i, EOL.length);
		i += EOL.length;
		return chunk;
	}

	private static boolean writeHTTPMsg(SocketConnection conn, HTTPMessage msg, byte[] data) {
		if(conn.isConnected() && !conn.isWritable()){
			// the socket should always be writable before sending a HTTP message over it under normal circumstances
			// if this is not checked, it may cause a CWE-400 vulnerability if an attacker continuously causes messages to be generated, but never receiving them,
			// eventually causing the write buffer to use all available resources
			// need to check for isConnected because this may be called before the socket is actually connected (isWritable may return false then)
			logger.warn("Tried to write HTTP message on blocked socket; destroying socket [DoS mitigation]");
			conn.destroy();
			return false;
		}
		StringBuilder sb = new StringBuilder(msg.getSize());
		if(msg.isRequest()){
			sb.append(msg.getMethod() + ' ' + msg.getPath() + ' ' + msg.getVersion());
		}else{
			sb.append(msg.getVersion() + ' ' + msg.getStatus());
		}
		sb.append("\r\n");
		for(Entry<String, String> header : msg.getHeaderSet()){
			if(header.getKey().equals("host"))
				sb.append("host: ").append(msg.getAuthority()).append("\r\n");
			else
				sb.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
		}
		sb.append("\r\n");
		conn.write(sb.toString().getBytes());
		if(data != null && data.length > 0)
			conn.write(data);
		return true;
	}
}
