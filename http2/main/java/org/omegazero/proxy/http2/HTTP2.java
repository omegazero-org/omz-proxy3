/*
 * Copyright (C) 2022 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

import org.omegazero.common.event.Tasks;
import org.omegazero.common.logging.Logger;
import org.omegazero.common.logging.LoggerUtil;
import org.omegazero.http.common.HTTPRequest;
import org.omegazero.http.common.HTTPResponse;
import org.omegazero.http.common.HTTPResponseData;
import org.omegazero.http.h2.HTTP2Client;
import org.omegazero.http.h2.HTTP2ConnectionError;
import org.omegazero.http.h2.HTTP2Endpoint;
import org.omegazero.http.h2.hpack.HPackContext;
import org.omegazero.http.h2.streams.ControlStream;
import org.omegazero.http.h2.streams.HTTP2Stream;
import org.omegazero.http.h2.streams.MessageStream;
import org.omegazero.http.h2.util.HTTP2Constants;
import org.omegazero.http.h2.util.HTTP2Settings;
import org.omegazero.http.h2.util.HTTP2Util;
import org.omegazero.http.netutil.SocketConnectionWritable;
import org.omegazero.net.common.NetCommon;
import org.omegazero.net.socket.AbstractSocketConnection;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.config.HTTPEngineConfig;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.core.ProxyEvents;
import org.omegazero.proxy.http.HTTPCommon;
import org.omegazero.proxy.http.HTTPEngine;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.HTTPEngineResponderMixin;
import org.omegazero.proxy.util.ProxyUtil;

import static org.omegazero.http.h2.util.HTTP2Constants.*;
import static org.omegazero.http.util.HTTPStatus.*;

public class HTTP2 extends HTTP2Endpoint implements HTTPEngine, HTTPEngineResponderMixin {

	private static final Logger logger = LoggerUtil.createLogger();

	static final String HTTP2_ALPN_NAME = "h2";
	private static final String[] HTTP2_ALPN = new String[] { HTTP2_ALPN_NAME };

	private static final String ATTACHMENT_KEY_UPSTREAM_SERVER = "engine_upstreamServer";
	private static final String ATTACHMENT_KEY_REQUEST_FINISHED = "engine_finished";
	private static final String ATTACHMENT_KEY_PENDING_RESPONSE = "engine_pendingresponse";
	private static final String ATTACHMENT_KEY_RESPONSE_TIMEOUT = "engine_responseTimeoutId";

	private static final String CONNDBG = "dbg";


	private final SocketConnection downstreamConnection;
	private final Proxy proxy;
	private final HTTPEngineConfig config;

	private final boolean disablePromiseRequestLog;

	private boolean downstreamClosed;
	private final String downstreamConnectionDbgstr;

	private boolean prefaceReceived = false;

	private final HTTP2Settings upstreamClientSettings;
	private Map<UpstreamServer, HTTP2Client> upstreamClients = new java.util.concurrent.ConcurrentHashMap<>();

	private int nextStreamId = 2;


	public HTTP2(SocketConnection downstreamConnection, Proxy proxy, HTTPEngineConfig config) {
		super(new SocketConnectionWritable(downstreamConnection), initSettings(config), new HPackContext.Session(), config.optBoolean("useHuffmanEncoding", true));
		super.closeWaitTimeout = config.optInt("closeWaitTimeout", 5) * 1000000000L;

		this.downstreamConnection = downstreamConnection;
		this.proxy = proxy;
		this.config = config;

		this.disablePromiseRequestLog = config.optBoolean("disablePromiseRequestLog", config.isDisableDefaultRequestLog());

		this.downstreamConnectionDbgstr = this.proxy.debugStringForConnection(this.downstreamConnection, null);

		this.upstreamClientSettings = new HTTP2Settings(super.settings);

		// limit encoder table size
		super.hpack.setEncoderDynamicTableMaxSizeCurrent(super.settings.get(SETTINGS_HEADER_TABLE_SIZE));

		this.downstreamConnection.setOnWritable(super::handleConnectionWindowUpdate);
	}

	private static HTTP2Settings initSettings(HTTPEngineConfig config) {
		HTTP2Settings settings = new HTTP2Settings();
		int maxFrameSize = config.optInt("maxFrameSize", 0);
		if(maxFrameSize > SETTINGS_MAX_FRAME_SIZE_MAX)
			throw new IllegalArgumentException("maxFrameSize is too large: " + maxFrameSize + " > " + SETTINGS_MAX_FRAME_SIZE_MAX);
		if(maxFrameSize > 0)
			settings.set(SETTINGS_MAX_FRAME_SIZE, maxFrameSize);

		int maxDynamicTableSize = config.optInt("maxDynamicTableSize", -1);
		if(maxDynamicTableSize >= 0)
			settings.set(SETTINGS_HEADER_TABLE_SIZE, maxDynamicTableSize);

		int initialWindowSize = config.optInt("initialWindowSize", 0);
		if(initialWindowSize > 0)
			settings.set(SETTINGS_INITIAL_WINDOW_SIZE, initialWindowSize);

		settings.set(SETTINGS_MAX_HEADER_LIST_SIZE, config.getMaxHeaderSize());
		settings.set(SETTINGS_MAX_CONCURRENT_STREAMS, config.optInt("maxConcurrentStreams", 100));
		return settings;
	}


	@Override
	public void processData(byte[] data) {
		int index = 0;
		while(index < data.length){
			if(this.prefaceReceived){
				index = super.processData0(data, index);
				if(index < 0)
					break;
			}else{
				if(HTTP2Util.isValidClientPreface(data)){
					index += HTTP2Util.getClientPrefaceLength();
					this.prefaceReceived = true;
					ControlStream cs = new ControlStream(super.connection, super.settings);
					super.streams.put(0, cs);
					cs.setOnSettingsUpdate((settings) -> {
						super.hpack.setEncoderDynamicTableMaxSizeSettings(settings.get(SETTINGS_HEADER_TABLE_SIZE));
						if(settings.get(SETTINGS_ENABLE_PUSH) == 0)
							this.upstreamClientSettings.set(SETTINGS_ENABLE_PUSH, 0);
					});
					cs.setOnWindowUpdate(super::handleConnectionWindowUpdate);
					try{
						cs.writeSettings(super.settings);
					}catch(IOException e){
						// the SocketConnection does not throw IOException on any methods
						throw new AssertionError(e);
					}
				}else{
					logger.debug(this.downstreamConnectionDbgstr, " Invalid client preface");
					this.downstreamConnection.destroy();
					break;
				}
			}
		}
	}

	@Override
	public void close() {
		this.downstreamClosed = true;
		for(HTTP2Client c : this.upstreamClients.values()){
			try{
				c.close();
			}catch(IOException e){
				throw new AssertionError(e);
			}
		}
	}

	@Override
	public SocketConnection getDownstreamConnection() {
		return this.downstreamConnection;
	}

	@Override
	public void respond(HTTPRequest request, HTTPResponseData responsedata) {
		if(!request.hasAttachment(MessageStream.ATTACHMENT_KEY_STREAM_ID))
			throw new IllegalArgumentException("request is not a HTTP/2 request (missing stream ID attachment)");
		int streamId = (int) request.getAttachment(MessageStream.ATTACHMENT_KEY_STREAM_ID);

		HTTPResponse response = responsedata.getHttpMessage();
		if(!HTTPCommon.setRequestResponse(request, response))
			return;
		MessageStream stream = (MessageStream) super.streams.get(streamId);
		if(stream == null)
			throw new IllegalStateException("Invalid stream");
		logger.debug(this.downstreamConnectionDbgstr, " Responding with status ", response.getStatus());

		response.deleteHeader("transfer-encoding");
		response.deleteHeader("connection");
		response.deleteHeader("keep-alive");
		response.deleteHeader("upgrade");

		byte[] data = responsedata.getData();
		data = HTTPCommon.prepareHTTPResponse(request, response, data);
		synchronized(stream){
			if(!stream.isExpectingResponse()) // fail silently
				return;
			synchronized(request){
				if(request.hasAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT))
					Tasks.clear((long) request.getAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT));
				if(request.hasAttachment(ATTACHMENT_KEY_REQUEST_FINISHED)){
					try{
						synchronized(super.hpack){
							if(data.length > 0){
								stream.sendHTTPMessage(response, false);
								stream.sendData(data, true);
							}else
								stream.sendHTTPMessage(response, true);
						}
					}catch(IOException e){
						throw new AssertionError(e);
					}
				}else
					request.setAttachment(ATTACHMENT_KEY_PENDING_RESPONSE, new HTTPResponseData(response, data.length > 0 ? data : null));
			}
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

	private void respondInternalError(HTTPRequest request, Throwable e) {
		try{
			this.respondError(request, STATUS_INTERNAL_SERVER_ERROR, HTTPCommon.MSG_SERVER_ERROR);
		}catch(Throwable e2){
			if(e != null)
				e.addSuppressed(e2);
		}
	}

	@Override
	public String getHTTPVersionName() {
		return MessageStream.HTTP2_VERSION_NAME;
	}


	@Override
	protected HTTP2Stream newStreamForFrame(int streamId, int type, int flags, byte[] payload) throws IOException {
		if(type == FRAME_TYPE_HEADERS){
			if((streamId & 1) == 0 || streamId <= super.highestStreamId)
				throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR);
			super.checkRemoteCreateStream();
			ControlStream cs = super.getControlStream();
			MessageStream mstream = new MessageStream(streamId, super.connection, cs, super.hpack);
			initMessageStream(mstream);
			logger.trace("Created new stream ", mstream.getStreamId(), " for HEADERS frame");
			Consumer<Integer> baseCloseHandler = (status) -> {
				logger.trace("Request stream ", mstream.getStreamId(), " closed with status ", HTTP2ConnectionError.getStatusCodeName(status));
				synchronized(super.closeWaitStreams){
					super.closeWaitStreams.add(mstream);
				}
			};
			mstream.setOnMessage((requestdata) -> {
				HTTPRequest request = (HTTPRequest) requestdata.getHttpMessage();
				boolean endStream = requestdata.isLastPacket();
				try{
					this.processHTTPRequest(mstream, request, endStream, baseCloseHandler);
				}catch(Exception e){
					if(e instanceof HTTP2ConnectionError)
						throw e;
					this.respondInternalError(request, e);
					logger.error("Error while processing request: ", e);
					if(endStream)
						this.endRequest(request, mstream, null);
					else
						throw new HTTP2ConnectionError(STATUS_INTERNAL_ERROR, true);
				}
			});
			mstream.setOnClosed(baseCloseHandler);
			return mstream;
		}
		return null;
	}


	private String initRequest(HTTPRequest request) {
		request.setHttpResponder(this);
		String requestId = HTTPCommon.requestId(this.downstreamConnection);
		request.setAttachment(HTTPCommon.ATTACHMENT_KEY_REQUEST_ID, requestId);
		if(this.config.isEnableHeaders())
			HTTPCommon.setDefaultHeaders(this.proxy, request);
		return requestId;
	}

	private synchronized void processHTTPRequest(MessageStream clientStream, HTTPRequest request, boolean endStream, Consumer<Integer> baseCloseHandler) throws IOException {
		String requestId = this.initRequest(request);
		this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_PRE_LOG, this.downstreamConnection, request);
		if(!this.config.isDisableDefaultRequestLog())
			logger.info(this.downstreamConnection.getApparentRemoteAddress(), "/", HTTPCommon.shortenRequestId(requestId), " - '", request.requestLine(), "'");

		MessageStream usStream = this.doHTTPRequestUpstream(clientStream, request);

		UpstreamServer userver = (UpstreamServer) request.getAttachment(ATTACHMENT_KEY_UPSTREAM_SERVER);
		assert usStream == null || userver != null; // userver is always non-null if usStream exists

		clientStream.setOnClosed((status) -> {
			baseCloseHandler.accept(status);
			if(usStream != null && !usStream.isClosed() && status != STATUS_NO_ERROR){
				try{
					usStream.rst(STATUS_CANCEL);
				}catch(IOException e){
					throw new AssertionError(e);
				}
			}
		});

		if(endStream){
			if(userver != null)
				this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_ENDED, this.downstreamConnection, request, userver);
			this.endRequest(request, clientStream, usStream);
		}else{
			clientStream.setOnData((requestdata) -> {
				try{
					if(userver != null){
						this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_DATA, this.downstreamConnection, requestdata, userver);
						if(usStream != null){
							if(usStream.isClosed()){
								if(!request.hasResponse())
									this.respondError(request, STATUS_BAD_GATEWAY, "Upstream message stream is no longer active");
							}else if(!usStream.sendData(requestdata.getData(), requestdata.isLastPacket()))
								clientStream.setReceiveData(false);
						}
						if(requestdata.isLastPacket())
							this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_ENDED, this.downstreamConnection, request, userver);
					}
				}catch(Exception e){
					if(e instanceof HTTP2ConnectionError)
						throw e;
					this.respondInternalError(request, e);
					logger.error("Error while processing request data: ", e);
				}
				if(requestdata.isLastPacket())
					this.endRequest(request, clientStream, usStream);
			});
			clientStream.setOnTrailers((trailers) -> {
				try{
					if(userver != null){
						this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_TRAILERS, this.downstreamConnection, trailers, userver);
						if(usStream != null){
							if(usStream.isClosed()){
								if(!request.hasResponse())
									this.respondError(request, STATUS_BAD_GATEWAY, "Upstream message stream is no longer active");
							}else
								usStream.sendTrailers(trailers);
						}
						this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_ENDED, this.downstreamConnection, request, userver); // trailers always imply EOS
					}
				}catch(Exception e){
					if(e instanceof HTTP2ConnectionError)
						throw e;
					this.respondInternalError(request, e);
					logger.error("Error while processing request trailers: ", e);
				}
				this.endRequest(request, clientStream, usStream);
			});
		}
		if(usStream != null){
			assert userver != null;

			usStream.setOnDataFlushed(() -> {
				clientStream.setReceiveData(true);
			});

			usStream.sendHTTPMessage(request, endStream);
		}
	}

	private MessageStream doHTTPRequestUpstream(MessageStream clientStream, HTTPRequest request) throws IOException {
		assert Thread.holdsLock(this);
		UpstreamServer userver = this.proxy.getUpstreamServer(request.getAuthority(), request.getPath());
		if(userver == null){
			logger.debug(this.downstreamConnectionDbgstr, " No upstream server found");
			this.proxy.dispatchEvent(ProxyEvents.INVALID_UPSTREAM_SERVER, this.downstreamConnection, request);
			this.respondError(request, STATUS_NOT_FOUND, HTTPCommon.MSG_NO_SERVER);
			return null;
		}

		this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_PRE, this.downstreamConnection, request, userver);
		if(request.hasResponse())
			return null;

		if(userver.getAddress() == null){
			this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST, this.downstreamConnection, request, userver);
			request.setAttachment(ATTACHMENT_KEY_UPSTREAM_SERVER, userver);
			return null;
		}

		final HTTP2Client client;
		synchronized(this){
			HTTP2Client tmp;
			tmp = this.upstreamClients.get(userver);
			if(tmp != null && !tmp.getConnection().isConnected()){
				logger.debug(this.downstreamConnectionDbgstr, " Upstream connection to ", tmp.getConnection().getRemoteName(), " no longer connected but still in map");
				this.upstreamClients.remove(userver, tmp);
				tmp = null;
			}
			if(tmp == null)
				tmp = this.createClient(request, userver);
			if(tmp == null)
				return null;
			client = tmp;
		}

		MessageStream usStream = client.createRequestStream();
		if(usStream == null)
			throw new HTTP2ConnectionError(HTTP2Constants.STATUS_REFUSED_STREAM);
		initMessageStream(usStream);
		if(logger.debug())
			logger.trace("Created new upstream request stream ", usStream.getStreamId(), " for request ", request.getAttachment(HTTPCommon.ATTACHMENT_KEY_REQUEST_ID));

		this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST, this.downstreamConnection, request, userver);
		request.setAttachment(ATTACHMENT_KEY_UPSTREAM_SERVER, userver);


		usStream.setOnPushPromise((promiseRequest) -> {
			if(!clientStream.isExpectingResponse())
				throw new HTTP2ConnectionError(HTTP2Constants.STATUS_CANCEL, true);

			String promiseRequestId = this.initRequest(promiseRequest);
			if(!this.disablePromiseRequestLog)
				logger.info(this.downstreamConnection.getApparentRemoteAddress(), "/", HTTPCommon.shortenRequestId(promiseRequestId), "/<promise> - '",
						promiseRequest.requestLine(), "'");

			int ppDSStreamId = this.nextStreamId;
			this.nextStreamId += 2;
			ControlStream cs = super.getControlStream();
			// The stream in the client connection where the push response is going to be sent on
			MessageStream ppDSStream = new MessageStream(ppDSStreamId, super.connection, cs, super.hpack);
			initMessageStream(ppDSStream);
			ppDSStream.preparePush(false);
			super.registerStream(ppDSStream);
			logger.trace("Created new downstream push promise stream ", ppDSStreamId, " for promise request ", promiseRequestId);

			// The stream in the upstream server connection where the push response is going to be received from
			MessageStream ppUSStream = client.handlePushPromise(promiseRequest);
			initMessageStream(ppUSStream);
			logger.trace("Created new upstream push promise stream ", ppUSStream.getStreamId(), " for promise request ", promiseRequestId);
			this.prepareResponseStream(promiseRequest, ppDSStream, ppUSStream, userver, client::onMessageStreamClosed);

			promiseRequest.setAttachment(MessageStream.ATTACHMENT_KEY_STREAM_ID, ppDSStreamId);
			promiseRequest.setAttachment(ATTACHMENT_KEY_UPSTREAM_SERVER, userver);

			ppDSStream.setOnClosed((status) -> {
				logger.trace("Push promise request stream ", ppDSStream.getStreamId(), " closed with status ", HTTP2ConnectionError.getStatusCodeName(status));
				synchronized(super.closeWaitStreams){
					super.closeWaitStreams.add(ppDSStream);
				}
				if(status != HTTP2Constants.STATUS_NO_ERROR){
					try{
						ppUSStream.rst(HTTP2Constants.STATUS_CANCEL);
					}catch(IOException e){
						throw new AssertionError(e);
					}
				}
			});

			this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST, this.downstreamConnection, promiseRequest, userver);
			this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_ENDED, this.downstreamConnection, promiseRequest, userver);
			this.endRequest(promiseRequest, ppDSStream, ppUSStream);

			// forward push promise to client
			clientStream.sendPushPromise(ppDSStreamId, promiseRequest);
		});
		this.prepareResponseStream(request, clientStream, usStream, userver, client::onMessageStreamClosed);

		return usStream;
	}

	private void endRequest(HTTPRequest request, MessageStream dsStream, MessageStream usStream) throws IOException {
		synchronized(dsStream){
			synchronized(request){
				request.setAttachment(ATTACHMENT_KEY_REQUEST_FINISHED, true);
				if(usStream == null && !request.hasResponse()){
					Exception e = new IllegalStateException("Non-forwarded request has no response after onHTTPRequestEnded");
					this.respondInternalError(request, e);
					logger.error(this.downstreamConnectionDbgstr, " endRequest: ", e);
					return;
				}
				HTTPResponseData res;
				if((res = (HTTPResponseData) request.removeAttachment(ATTACHMENT_KEY_PENDING_RESPONSE)) != null){
					synchronized(super.hpack){
						if(res.getData() != null){
							dsStream.sendHTTPMessage(res.getHttpMessage(), false);
							dsStream.sendData(res.getData(), true);
						}else
							dsStream.sendHTTPMessage(res.getHttpMessage(), true);
					}
				}else if(usStream != null && this.config.getResponseTimeout() > 0){
					long tid = Tasks.timeout(() -> {
						if(this.downstreamClosed || dsStream.isClosed())
							return;
						AbstractSocketConnection uconn = (AbstractSocketConnection) ((SocketConnectionWritable) usStream.getConnection()).getConnection();
						UpstreamServer userver = (UpstreamServer) request.getAttachment(ATTACHMENT_KEY_UPSTREAM_SERVER);
						logUNetError(uconn.getAttachment(CONNDBG), " Response timeout");
						try{
							this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_TIMEOUT, this.downstreamConnection, uconn, request, userver);
							this.respondUNetError(request, STATUS_GATEWAY_TIMEOUT, HTTPCommon.MSG_UPSTREAM_RESPONSE_TIMEOUT, uconn, userver);
							usStream.rst(STATUS_CANCEL);
						}catch(Exception e){
							this.respondInternalError(request, e);
							logger.error(uconn.getAttachment(CONNDBG), " Error while handling response timeout: ", e);
						}
					}, this.config.getResponseTimeout()).daemon().getId();
					request.setAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT, tid);
				}
			}
		}
	}


	private void prepareResponseStream(HTTPRequest request, MessageStream dsStream, MessageStream usStream, UpstreamServer userver,
			java.util.function.Consumer<MessageStream> streamClosedHandler) {
		AbstractSocketConnection uconn = (AbstractSocketConnection) ((SocketConnectionWritable) usStream.getConnection()).getConnection();
		usStream.setOnMessage((responsedata) -> {
			synchronized(dsStream){
				if(dsStream.isClosed())
					throw new HTTP2ConnectionError(HTTP2Constants.STATUS_CANCEL, true);
				HTTPResponse response = (HTTPResponse) responsedata.getHttpMessage();
				try{
					if(request.hasAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT))
						Tasks.clear((long) request.getAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT));

					if(!HTTPCommon.setRequestResponse(request, response))
						return;
					response.setOther(request);
					if(this.config.isEnableHeaders()){
						if(!response.headerExists("date"))
							response.setHeader("date", HTTPCommon.dateString());
						HTTPCommon.setDefaultHeaders(this.proxy, response);
					}

					boolean wasChunked = response.isChunkedTransfer();
					this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE, this.downstreamConnection, uconn, response, userver);
					if(wasChunked && !response.isChunkedTransfer())
						throw new IllegalStateException("Cannot unchunkify a response body");
					else if(response.isChunkedTransfer() && !wasChunked)
						response.deleteHeader("content-length");
					if(response.isIntermediateMessage())
						request.setOther(null);
					synchronized(request){
						synchronized(super.hpack){
							dsStream.sendHTTPMessage(response, responsedata.isLastPacket());
						}
					}
				}catch(Exception e){
					request.setOther(null);
					if(e instanceof HTTP2ConnectionError)
						throw e;
					this.respondInternalError(request, e);
					logger.error("Error while processing response: ", e);
				}
			}
		});
		usStream.setOnData((responsedata) -> {
			synchronized(dsStream){
				if(dsStream.isClosed())
					throw new HTTP2ConnectionError(HTTP2Constants.STATUS_CANCEL, true);
				this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_DATA, this.downstreamConnection, uconn, responsedata, userver);
				if(!dsStream.sendData(responsedata.getData(), responsedata.isLastPacket()))
					usStream.setReceiveData(false);
			}
		});
		usStream.setOnTrailers((trailers) -> {
			synchronized(dsStream){
				if(dsStream.isClosed())
					throw new HTTP2ConnectionError(HTTP2Constants.STATUS_CANCEL, true);
				this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_TRAILERS, this.downstreamConnection, uconn, trailers, userver);
				// HTTP_RESPONSE_ENDED event is already run in onClosed below because the response stream is complete and closes immediately after receiving trailers
				synchronized(request){
					synchronized(super.hpack){
						dsStream.sendTrailers(trailers);
					}
				}
			}
		});
		usStream.setOnClosed((status) -> {
			if(this.downstreamClosed)
				return;
			logger.trace("Upstream request stream ", usStream.getStreamId(), " closed with status ", HTTP2ConnectionError.getStatusCodeName(status));
			streamClosedHandler.accept(usStream);
			HTTPResponse response = request.getOther();
			if(response == null){
				if(status == HTTP2Constants.STATUS_ENHANCE_YOUR_CALM || status == HTTP2Constants.STATUS_HTTP_1_1_REQUIRED){ // passthrough error to client
					try{
						dsStream.rst(status);
					}catch(IOException e){
						throw new AssertionError(e);
					}
				}else if(dsStream.isExpectingResponse()){
					logUNetError(uconn.getAttachment(CONNDBG), " Stream ", usStream.getStreamId(), " closed unexpectedly with status ",
							HTTP2ConnectionError.getStatusCodeName(status));
					this.respondUNetError(request, STATUS_BAD_GATEWAY, "Upstream message stream closed unexpectedly", uconn, userver);
				}
			}else if(status == 0)
				this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_ENDED, this.downstreamConnection, uconn, response, userver);
		});

		dsStream.setOnDataFlushed(() -> {
			usStream.setReceiveData(true);
		});
	}


	private HTTP2Client createClient(HTTPRequest request, UpstreamServer userver) throws IOException {
		assert Thread.holdsLock(this);
		if(!userver.isProtocolSupported("http/2"))
			throw new HTTP2ConnectionError(HTTP2Constants.STATUS_HTTP_1_1_REQUIRED, true, "HTTP/2 is not supported by the upstream server");

		AbstractSocketConnection uconn;
		try{
			uconn = (AbstractSocketConnection) ProxyUtil.connectUpstreamTCP(this.proxy, this.downstreamConnection, true, userver, HTTP2_ALPN);
		}catch(IOException e){
			this.respondInternalError(request, e);
			logger.error("Connection failed: ", e);
			return null;
		}

		HTTP2Client client = new ProxyHTTP2Client(uconn, this.upstreamClientSettings, super.hpack.getSession(), super.hpack.isUseHuffmanEncoding());

		uconn.setAttachment(CONNDBG, this.proxy.debugStringForConnection(this.downstreamConnection, uconn));
		uconn.setOnConnect(() -> {
			logger.debug(uconn.getAttachment(CONNDBG), " Connected");
			this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION, uconn);
		});
		uconn.setOnTimeout(() -> {
			logUNetError(uconn.getAttachment(CONNDBG), " Connect timed out");
			this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_TIMEOUT, uconn);
			if(this.downstreamClosed)
				return;
			this.respondUNetError(this.proxy, request, STATUS_GATEWAY_TIMEOUT, HTTPCommon.MSG_UPSTREAM_CONNECT_TIMEOUT, uconn, userver);
		});
		uconn.setOnError((e) -> {
			Exception e2 = null;
			try{
				this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_ERROR, uconn, e);

				if(this.downstreamClosed)
					return;
				if(e instanceof HTTP2ConnectionError)
					this.respondUNetError(this.proxy, request, STATUS_BAD_GATEWAY, "HTTP/2 protocol error", uconn, userver);
				else if(e instanceof IOException)
					this.respondUNetError(this.proxy, request, STATUS_BAD_GATEWAY, HTTPCommon.getUpstreamErrorMessage(e), uconn, userver);
				else
					this.respondError(request, STATUS_INTERNAL_SERVER_ERROR, HTTPCommon.MSG_SERVER_ERROR);
			}catch(Exception ue){
				e2 = ue;
			}
			if(e2 != null)
				this.respondInternalError(request, e2);
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
			this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_CLOSED, uconn);
			synchronized(this){
				if(!this.downstreamClosed)
					client.close(); // close here to send 502 response(s) if necessary
			}
			this.upstreamClients.remove(userver, client);
		});
		uconn.setOnData(client::processData);

		client.start();
		uconn.connect(this.config.getUpstreamConnectionTimeout());
		this.upstreamClients.put(userver, client);
		return client;
	}

	private static void initMessageStream(MessageStream ms) {
		ms.setRequestSupplier(org.omegazero.proxy.http.ProxyHTTPRequest::new);
		ms.setResponseSupplier(org.omegazero.proxy.http.ProxyHTTPResponse::new);
	}

	private static void logUNetError(Object... o) {
		if(HTTPCommon.USOCKET_ERROR_DEBUG)
			logger.debug(o);
		else
			logger.warn(o);
	}
}
