/*
 * Copyright (C) 2023 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.omegazero.common.event.Tasks;
import org.omegazero.common.eventbus.EventResult;
import org.omegazero.common.logging.Logger;
import org.omegazero.http.common.HTTPException;
import org.omegazero.http.common.HTTPMessage;
import org.omegazero.http.common.HTTPRequest;
import org.omegazero.http.common.HTTPRequestData;
import org.omegazero.http.common.HTTPResponse;
import org.omegazero.http.common.HTTPResponseData;
import org.omegazero.http.common.MessageStreamClosedException;
import org.omegazero.http.netutil.SocketConnectionWritable;
import org.omegazero.http.util.HTTPClient;
import org.omegazero.http.util.HTTPClientStream;
import org.omegazero.http.util.HTTPServer;
import org.omegazero.http.util.HTTPServerStream;
import org.omegazero.net.common.NetCommon;
import org.omegazero.net.socket.AbstractSocketConnection;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.config.HTTPEngineConfig;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.core.ProxyEvents;
import org.omegazero.proxy.core.ProxyRegistry;
import org.omegazero.proxy.http.HTTPCommon;
import org.omegazero.proxy.http.HTTPEngine;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.HTTPEngineResponderMixin;
import org.omegazero.proxy.util.ProxyUtil;

import static org.omegazero.http.util.HTTPStatus.*;

/**
 * A {@link HTTPEngine} with common method implementations.
 * <p>
 * This implementation uses a single {@link HTTPServer} passed in the constructor as the client request processor and any amount of {@link HTTPClient}s to send requests to {@link UpstreamServer}s
 * using an appropriate procotol.
 * <p>
 * {@code HTTPServer} and {@code HTTPClient} implementations used for this {@code AbstractHTTPEngine} need not be thread-safe.
 * 
 * @since 3.10.1
 * @see ProxyRegistry#registerHTTPClientImplementation(String, ProxyRegistry.HTTPClientConstructor, String)
 */
public abstract class AbstractHTTPEngine implements HTTPEngine, HTTPEngineResponderMixin {

	private static final Logger logger = Logger.create();

	public static final String ATTACHMENT_KEY_UPSTREAM_SERVER = "engine_userver";
	public static final String ATTACHMENT_KEY_USERVER_CLIENT = "engine_usc";
	public static final String ATTACHMENT_KEY_RESPONSE_TIMEOUT = "engine_responseTimeoutId";

	protected static final String CONNDBG = "dbg";

	protected final SocketConnection downstreamConnection;
	protected final Proxy proxy;
	protected final HTTPEngineConfig config;
	protected final HTTPServer httpServer;

	protected final String downstreamConnectionDbgstr;
	protected final boolean disablePromiseRequestLog;

	protected boolean downstreamClosed;

	protected final Map<UpstreamServer, HTTPClientSet> upstreamClients = new java.util.HashMap<>();

	protected SocketConnection switchedProtocolUpstreamConnection = null;

	/**
	 * Creates a new {@code AbstractHTTPEngine}.
	 *
	 * @param downstreamConnection The client connection
	 * @param proxy The {@link Proxy} instance
	 * @param httpServer The {@link HTTPServer} implementation to use
	 */
	public AbstractHTTPEngine(SocketConnection downstreamConnection, Proxy proxy, HTTPEngineConfig config, HTTPServer httpServer){
		this.downstreamConnection = Objects.requireNonNull(downstreamConnection);
		this.proxy = Objects.requireNonNull(proxy);
		this.config = Objects.requireNonNull(config);
		this.httpServer = Objects.requireNonNull(httpServer);

		this.downstreamConnectionDbgstr = this.proxy.debugStringForConnection(this.downstreamConnection, null);
		this.disablePromiseRequestLog = config.optBoolean("disablePromiseRequestLog", config.isDisableDefaultRequestLog());

		if(logger.debug())
			logger.debug(this.downstreamConnectionDbgstr, " Using ", this.httpServer.getClass().getName(), " for this connection");

		this.httpServer.onNewRequest(this::receiveNewRequest);
	}


	/**
	 * {@inheritDoc}
	 * <p>
	 * This string is also used as the protocol name of this implementation.
	 */
	@Override
	public abstract String getHTTPVersionName();

	/**
	 * Returns {@code true} if the downstream (client) connection is encrypted.
	 *
	 * @return {@code} if encrypted
	 */
	public abstract boolean isDownstreamConnectionSecure();

	/**
	 * Returns the {@link Logger} requests should be logged with.
	 *
	 * @return The {@code Logger}
	 */
	public abstract Logger getRequestLogger();


	@Override
	public void processData(byte[] data){
		try{
			if(this.switchedProtocolUpstreamConnection != null){
				ProxyUtil.handleBackpressure(this.switchedProtocolUpstreamConnection, this.downstreamConnection);
				this.switchedProtocolUpstreamConnection.write(data);
			}else
				this.httpServer.receive(data);
		}catch(Exception e){
			logger.error(this.downstreamConnectionDbgstr, " Uncaught error while processing packet: ", e);
			this.downstreamConnection.destroy();
		}
	}

	@Override
	public void close(){
		this.downstreamClosed = true;
		for(HTTPClientSet clientset : this.upstreamClients.values())
			clientset.closeAll();
		this.httpServer.close();
	}

	@Override
	public SocketConnection getDownstreamConnection(){
		return this.downstreamConnection;
	}

	@Override
	public void respond(HTTPRequest request, HTTPResponseData responsedata){
		this.httpServer.respond(request, responsedata);
		if(request != null && request.hasAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT))
			Tasks.I.clear(request.removeAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT));
		request.removeAttachment(ATTACHMENT_KEY_USERVER_CLIENT);
	}

	@Override
	public void respond(HTTPRequest request, int status, byte[] data, String... headers){
		this.respondEx(this.proxy, request, status, data, headers);
	}

	@Override
	public void respondError(HTTPRequest request, int status, String title, String message, String... headers){
		this.respondError(this.proxy, request, status, title, message, headers);
	}

	protected void respondUNetError(HTTPRequest request, int status, String message, SocketConnection uconn, UpstreamServer userver) {
		this.respondUNetError(this.proxy, request, status, message, uconn, userver);
	}

	protected void respondInternalError(HTTPRequest request, Throwable e) {
		try{
			this.respondError(request, STATUS_INTERNAL_SERVER_ERROR, HTTPCommon.MSG_SERVER_ERROR);
		}catch(Throwable e2){
			if(e != null)
				e.addSuppressed(e2);
		}
	}

	private void endRequestsForUClient(HTTPClient client, Consumer<HTTPServerStream> callback){
		for(HTTPServerStream req : this.httpServer.getActiveRequests()){
			HTTPClient uclientR = (HTTPClient) req.getRequest().getAttachment(ATTACHMENT_KEY_USERVER_CLIENT);
			if(client == uclientR){
				if(req.getRequest().hasResponse()){
					req.close();
				}else
					callback.accept(req);
			}
		}
	}

	private void handleUpstreamMessageStreamError(HTTPServerStream req, Throwable err, AbstractSocketConnection uconn, UpstreamServer userver){
		if(err instanceof org.omegazero.common.event.task.ExecutionFailedException)
			err = err.getCause();
		Exception e2 = null;
		try{
			if(!req.getRequest().hasResponse()){
				if(err instanceof IOException)
					this.respondUNetError(req.getRequest(), STATUS_BAD_GATEWAY, HTTPCommon.getUpstreamErrorMessage(err), uconn, userver);
				else
					this.respondInternalError(req.getRequest(), err);
			}else
				req.close(MessageStreamClosedException.CloseReason.INTERNAL_ERROR);
		}catch(Exception ue){
			this.respondInternalError(req.getRequest(), ue);
			e2 = ue;
		}
		if(err instanceof IOException){
			logUNetError(uconn.getAttachment(CONNDBG), " Error: ", NetCommon.PRINT_STACK_TRACES ? err : err.toString());
			if(e2 != null)
				logger.error(uconn.getAttachment(CONNDBG), " Internal error while handling upstream stream error: ", e2);
		}else{
			if(e2 != null)
				err.addSuppressed(e2);
			logger.error(uconn.getAttachment(CONNDBG), " Internal error: ", err);
		}
	}


	private HTTPClientStream createClientStream(HTTPServerStream req){
		HTTPRequest request = req.getRequest();
		EventResult userverRes = this.proxy.dispatchEventRes(ProxyEvents.HTTP_REQUEST_SELECT_SERVER, this.downstreamConnection, request);
		if(request.hasResponse())
			return null;
		UpstreamServer userver = (UpstreamServer) userverRes.getReturnValue();
		if(userver == null)
			userver = this.proxy.getDefaultUpstreamServer();
		if(userver == null){
			logger.debug(this.downstreamConnectionDbgstr, " No upstream server found");
			this.proxy.dispatchEvent(ProxyEvents.INVALID_UPSTREAM_SERVER, this.downstreamConnection, request);
			this.respondError(request, STATUS_NOT_FOUND, HTTPCommon.MSG_NO_SERVER);
			return null;
		}

		this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_PRE, this.downstreamConnection, request, userver);
		if(request.hasResponse())
			return null;

		request.setAttachment(ATTACHMENT_KEY_UPSTREAM_SERVER, userver);

		HTTPClientSet clientset;
		if(userver.getAddress() != null){
			clientset = this.upstreamClients.get(userver);
			if(clientset == null){
				clientset = new HTTPClientSet(userver);
				if(!clientset.initFirstClient(request)){
					assert request.hasResponse();
					return null;
				}
			}
			this.upstreamClients.put(userver, clientset);
		}else
			clientset = null;

		boolean wasChunked = request.isChunkedTransfer();
		this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST, this.downstreamConnection, request, userver);
		if(clientset == null)
			return null;
		if(wasChunked && !request.isChunkedTransfer())
			throw new IllegalStateException("Cannot unchunkify a request body");
		else if(request.isChunkedTransfer() && !wasChunked)
			request.deleteHeader("content-length");

		HTTPClientStream ureq = clientset.newRequest(request);
		if(ureq == null){ // no request stream could be created, abort client request
			if(logger.debug())
				logger.debug("Could not create upstream HTTPClientStream for request ", request.getAttachment(HTTPCommon.ATTACHMENT_KEY_REQUEST_ID), ", aborting client request");
			req.close(MessageStreamClosedException.CloseReason.REFUSED);
		}else{
			request.setAttachment(ATTACHMENT_KEY_USERVER_CLIENT, ureq.getClient());
		}
		return ureq;
	}

	private HTTPClient createClient(UpstreamServer userver, HTTPRequest initrequest){
		// ! use of initrequest in callbacks is disallowed; only use for respondError
		ProxyRegistry.HTTPClientConstructor constructor = null;
		String protocol = this.getHTTPVersionName();
		if(userver.isProtocolSupported(protocol))
			constructor = this.proxy.getRegistry().getHTTPClientImplementation(protocol);
		if(constructor == null){
			Collection<String> supported = userver.getSupportedProcotols();
			if(supported == UpstreamServer.PROTOCOLS_ALL)
				supported = UpstreamServer.PROTOCOLS_DEFAULT;
			for(String proto : supported){
				constructor = this.proxy.getRegistry().getHTTPClientImplementation(proto);
				if(constructor != null){
					protocol = proto;
					break;
				}
			}
		}
		if(constructor == null){
			this.respondError(initrequest, STATUS_HTTP_VERSION_NOT_SUPPORTED, HTTPCommon.MSG_PROTO_NOT_SUPPORTED + this.getHTTPVersionName());
			return null;
		}

		AbstractSocketConnection uconn;
		try{
			uconn = (AbstractSocketConnection) ProxyUtil.connectUpstreamTCP(this.proxy, this.downstreamConnection, this.isDownstreamConnectionSecure(),
					userver, new String[] { this.proxy.getRegistry().getHTTPClientALPName(protocol) });
		}catch(IOException e){
			this.respondInternalError(initrequest, e);
			logger.error("Connection failed: ", e);
			return null;
		}
		uconn.setAttachment(CONNDBG, this.proxy.debugStringForConnection(this.downstreamConnection, uconn));

		HTTPClient client = constructor.construct(uconn, userver, this.config, this.httpServer);
		if(client == null){
			uconn.destroy();
			throw new NullPointerException("client is null");
		}
		if(logger.debug())
			logger.debug(this.downstreamConnectionDbgstr, " Using ", client.getClass().getName(), " (protocol '", protocol, "') with ", uconn, " to connect to ", userver);
		client.setServerPushEnabled(this.httpServer.isServerPushEnabled());

		uconn.on("connect", () -> {
			logger.debug(uconn.getAttachment(CONNDBG), " Connected");
			this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION, uconn);
		});
		uconn.on("timeout", () -> {
			logUNetError(uconn.getAttachment(CONNDBG), " Connect timed out");
			this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_TIMEOUT, uconn);
			if(this.downstreamClosed)
				return;
			this.endRequestsForUClient(client, (req) -> {
				this.respondUNetError(req.getRequest(), STATUS_GATEWAY_TIMEOUT, HTTPCommon.MSG_UPSTREAM_CONNECT_TIMEOUT, uconn, userver);
			});
		});
		uconn.on("error", (Throwable e) -> {
			if(e instanceof org.omegazero.common.event.task.ExecutionFailedException)
				e = e.getCause();
			try{
				this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_ERROR, uconn, e);
			}catch(Exception ue){
				e.addSuppressed(ue);
			}
			if(this.downstreamClosed)
				return;
			final Throwable e0 = e;
			uconn.getWorker().accept(() -> {
				this.endRequestsForUClient(client, (req) -> {
					this.handleUpstreamMessageStreamError(req, e0, uconn, userver);
				});
			});
		});
		uconn.on("close", () -> {
			logger.debug(uconn.getAttachment(CONNDBG), " Disconnected");
			this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_CLOSED, uconn);
			if(!this.downstreamClosed){ // respond to all incomplete requests for this connection with an error
				this.endRequestsForUClient(client, (req) -> {
					this.respondUNetError(req.getRequest(), STATUS_BAD_GATEWAY, HTTPCommon.MSG_UPSTREAM_CONNECTION_CLOSED, uconn, userver);
				});
			}
			HTTPClientSet clientset = this.upstreamClients.get(userver);
			clientset.remove(client);
			if(clientset.isEmpty())
				this.upstreamClients.remove(userver);
		});
		uconn.on("data", (byte[] data) -> {
			if(this.switchedProtocolUpstreamConnection == uconn){
				ProxyUtil.handleBackpressure(this.downstreamConnection, uconn);
				this.downstreamConnection.write(data);
			}else
				client.receive(data);
		});

		uconn.connect(this.config.getUpstreamConnectionTimeout());
		return client;
	}

	private HTTPClientStream setupRequestStream(HTTPServerStream req){
		HTTPRequest request = req.getRequest();
		HTTPClientStream ureq0;
		try{
			String requestId = this.initRequest(request);

			this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_PRE_LOG, this.downstreamConnection, request);
			if(!this.config.isDisableDefaultRequestLog())
				this.getRequestLogger().info(this.downstreamConnection.getApparentRemoteAddress(), "/", HTTPCommon.shortenRequestId(requestId), " - '", request.requestLine(), "'");

			ureq0 = this.createClientStream(req);
		}catch(Exception e){
			ureq0 = null;
			this.respondInternalError(request, e);
			logger.error("Error while handling request: ", e);
		}
		HTTPClientStream ureq = ureq0;
		if(req.isClosed()) // request aborted
			return null;
		UpstreamServer userver = (UpstreamServer) request.getAttachment(ATTACHMENT_KEY_UPSTREAM_SERVER);
		assert !(ureq != null && userver == null) : "ureq exists but userver is null";

		req.onError((err) -> {
			logger.debug(this.downstreamConnectionDbgstr, " Request stream error: ", err);
			if(ureq != null)
				ureq.close();
		});
		req.onRequestData((reqdata) -> {
			try{
				if(userver != null){
					this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_DATA, this.downstreamConnection, reqdata, userver);
					if(ureq != null){
						if(ureq.isClosed()){
							if(!request.hasResponse())
								this.respondError(request, STATUS_BAD_GATEWAY, "Upstream message stream is no longer active");
						}else if(!ureq.sendRequestData(reqdata.getData(), false))
							req.setReceiveData(false);
					}
				}
			}catch(Exception e){
				this.respondInternalError(request, e);
				logger.error("Error while processing request data: ", e);
			}
		});
		req.onRequestEnded((trailers) -> {
			try{
				if(userver != null){
					if(trailers != null)
						this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_TRAILERS, this.downstreamConnection, trailers, userver);
					if(ureq != null){
						if(ureq.isClosed()){
							if(!request.hasResponse())
								this.respondError(request, STATUS_BAD_GATEWAY, "Upstream message stream is no longer active");
						}else
							ureq.endRequest(trailers);
					}
					this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_ENDED, this.downstreamConnection, request, userver);
				}
			}catch(Exception e){
				this.respondInternalError(request, e);
				logger.error("Error while processing end of request: ", e);
			}
			this.requestEnded(request, req, ureq);
		});
		return ureq;
	}

	private void setupResponseStream(HTTPServerStream req, HTTPClientStream ureq, SocketConnection uconn){
		HTTPRequest request = req.getRequest();
		UpstreamServer userver = (UpstreamServer) request.getAttachment(ATTACHMENT_KEY_UPSTREAM_SERVER);
		ureq.onServerPush((resstream) -> {
			HTTPRequest promiseRequest = resstream.getRequest();
			String promiseRequestId = this.initRequest(promiseRequest);
			if(!this.disablePromiseRequestLog)
				this.getRequestLogger().info(this.downstreamConnection.getApparentRemoteAddress(), "/", HTTPCommon.shortenRequestId(promiseRequestId), "/<promise> - '",
						promiseRequest.requestLine(), "'");

			HTTPServerStream dsstream = req.startServerPush(promiseRequest);
			if(dsstream == null){
				logger.debug(this.downstreamConnectionDbgstr, " Failed to create push promise stream, aborting upstream push promise");
				resstream.close();
				return;
			}

			try{
				this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST, this.downstreamConnection, promiseRequest, userver);
				this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_ENDED, this.downstreamConnection, promiseRequest, userver);

				dsstream.onError((err) -> {
					logger.debug(this.downstreamConnectionDbgstr, " Downstream push promise stream error: ", err);
					resstream.close();
				});
				this.setupResponseStreamBase(dsstream, resstream, (AbstractSocketConnection) uconn, userver);
				this.requestEnded(promiseRequest, dsstream, resstream);
			}catch(Exception e){
				this.respondInternalError(promiseRequest, e);
				logger.error("Error while handling push promise request: ", e);
				resstream.close();
				this.requestEnded(promiseRequest, dsstream, null);
			}
		});
		this.setupResponseStreamBase(req, ureq, (AbstractSocketConnection) uconn, userver);
	}

	private void setupResponseStreamBase(HTTPServerStream req, HTTPClientStream ureq, AbstractSocketConnection uconn, UpstreamServer userver){
		HTTPRequest request = req.getRequest();
		ureq.onResponse((response) -> {
			synchronized(req){
				if(req.isClosed()){
					ureq.close();
					return;
				}

				if(request.hasAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT))
					Tasks.I.clear(request.removeAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT));

				if(!HTTPCommon.setRequestResponse(request, response)){
					ureq.close();
					return;
				}
				try{
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
					if(response.getStatus() == STATUS_SWITCHING_PROTOCOLS && response.headerExists("upgrade")){
						this.procotolChanged(uconn, ureq, response);
					}else if(response.isIntermediateMessage())
						request.setOther(null);
				}catch(Exception e){
					request.setOther(null); // reset to allow respondInternalError to write a response
					throw e;
				}
				request.removeAttachment(ATTACHMENT_KEY_USERVER_CLIENT);
				req.startResponse(response);
			}
		});
		ureq.onResponseData((resdata) -> {
			synchronized(req){
				if(req.isClosed()){
					ureq.close();
					return;
				}
				this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_DATA, this.downstreamConnection, uconn, resdata, userver);
				if(!req.sendResponseData(resdata.getData(), false))
					ureq.setReceiveData(false);
			}
		});
		ureq.onResponseEnded((trailers) -> {
			synchronized(req){
				if(req.isClosed()){
					ureq.close();
					return;
				}
				if(trailers != null)
					this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_TRAILERS, this.downstreamConnection, uconn, trailers, userver);
				req.endResponse(trailers);
			}
			this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_ENDED, this.downstreamConnection, uconn, ureq.getResponse(), userver);
		});
		ureq.onError((err) -> {
			synchronized(req){
				if(req.isClosed()){
					ureq.close();
					return;
				}
				if(err instanceof MessageStreamClosedException){
					MessageStreamClosedException.CloseReason reason = ((MessageStreamClosedException) err).getCloseReason();
					if(reason == MessageStreamClosedException.CloseReason.PROTOCOL_DOWNGRADE || reason == MessageStreamClosedException.CloseReason.ENHANCE_YOUR_CALM){
						req.close(reason);
						return;
					}
				}
				this.handleUpstreamMessageStreamError(req, err, uconn, userver);
			}
		});

		ureq.onWritable(() -> {
			req.setReceiveData(true);
		});
		req.onWritable(() -> {
			ureq.setReceiveData(true);
		});
	}

	private void procotolChanged(SocketConnection uconn, HTTPClientStream ureq, HTTPResponse response){
		if(logger.debug())
			logger.debug(((AbstractSocketConnection) uconn).getAttachment(CONNDBG), " Protocol changed, closing all clients except ", ureq.getClient());
		this.proxy.dispatchEvent(ProxyEvents.PROTOCOL_SWITCHED, this.downstreamConnection, uconn, response);
		this.switchedProtocolUpstreamConnection = uconn;
		Iterator<HTTPClientSet> clientsetIt = this.upstreamClients.values().iterator();
		while(clientsetIt.hasNext()){
			HTTPClientSet clientset = clientsetIt.next();
			clientset.closeIf((c) -> c != ureq.getClient());
			if(clientset.isEmpty())
				clientsetIt.remove();
		}
	}

	private void requestEnded(HTTPRequest request, HTTPServerStream req, HTTPClientStream ureq){
		synchronized(req){
			try{
				if(ureq == null && !request.hasResponse())
					throw new IllegalStateException("Non-forwarded request has no response after onHTTPRequestEnded");
			}catch(Exception e){
				this.respondInternalError(request, e);
				logger.error(this.downstreamConnectionDbgstr, " requestEnded: ", e);
				return;
			}
			if(ureq != null && this.config.getResponseTimeout() > 0){
				Object tid = Tasks.I.timeout(() -> {
					if(this.downstreamClosed || req.isClosed())
						return;
					AbstractSocketConnection uconn = (AbstractSocketConnection) ((SocketConnectionWritable) ureq.getClient().getConnection()).getConnection();
					UpstreamServer userver = (UpstreamServer) request.getAttachment(ATTACHMENT_KEY_UPSTREAM_SERVER);
					logUNetError(uconn.getAttachment(CONNDBG), " Response timeout");
					try{
						this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_TIMEOUT, this.downstreamConnection, uconn, request, userver);
						this.respondUNetError(request, STATUS_GATEWAY_TIMEOUT, HTTPCommon.MSG_UPSTREAM_RESPONSE_TIMEOUT, uconn, userver);
						ureq.close();
					}catch(Exception e){
						this.respondInternalError(request, e);
						logger.error(uconn.getAttachment(CONNDBG), " Error while handling response timeout: ", e);
					}
				}, this.config.getResponseTimeout()).daemon();
				request.setAttachment(ATTACHMENT_KEY_RESPONSE_TIMEOUT, tid);
			}
		}
	}


	private void receiveNewRequest(HTTPServerStream req){
		HTTPClientStream ureq = this.setupRequestStream(req);
		if(ureq != null){
			this.setupResponseStream(req, ureq, ((org.omegazero.http.netutil.SocketConnectionWritable) ureq.getClient().getConnection()).getConnection());
			ureq.startRequest();
		}
	}

	private String initRequest(HTTPRequest request){
		request.setHttpResponder(this);
		String requestId = HTTPCommon.requestId(this.downstreamConnection);
		request.setAttachment(HTTPCommon.ATTACHMENT_KEY_REQUEST_ID, requestId);
		if(this.config.isEnableHeaders())
			HTTPCommon.setDefaultHeaders(this.proxy, request);
		return requestId;
	}


	private class HTTPClientSet {

		private final UpstreamServer userver;

		private Set<HTTPClient> clients = new java.util.HashSet<>();

		public HTTPClientSet(UpstreamServer userver){
			this.userver = userver;
		}


		private HTTPClient newClient(HTTPRequest request){
			int maxStreams = AbstractHTTPEngine.this.config.getMaxStreamsPerServer();
			int currentMaxStreams = 0;
			for(HTTPClient client : this.clients)
				currentMaxStreams += client.getMaxConcurrentRequestCount();
			if(currentMaxStreams >= AbstractHTTPEngine.this.config.getMaxStreamsPerServer())
				return null;
			logger.debug(AbstractHTTPEngine.this.downstreamConnectionDbgstr, " Creating new HTTPClient instance (", currentMaxStreams, "+n of ", maxStreams, " streams total)");
			HTTPClient newClient = AbstractHTTPEngine.this.createClient(this.userver, request);
			if(newClient != null)
				this.clients.add(newClient);
			return newClient;
		}

		public boolean initFirstClient(HTTPRequest request){
			return this.newClient(request) != null;
		}

		public HTTPClientStream newRequest(HTTPRequest request){
			HTTPClientStream stream = null;
			Iterator<HTTPClient> clientIt = this.clients.iterator();
			while(clientIt.hasNext()){
				HTTPClient client = clientIt.next();
				if(((SocketConnectionWritable) client.getConnection()).getConnection().hasDisconnected()){
					logger.debug(AbstractHTTPEngine.this.downstreamConnectionDbgstr, " Upstream connection to ", client.getConnection().getRemoteName(), " no longer connected but still in map");
					clientIt.remove();
					continue;
				}
				stream = client.newRequest(request);
				if(stream != null)
					return stream;
			}
			if(stream == null){
				HTTPClient newClient = this.newClient(request);
				if(newClient != null){
					stream = newClient.newRequest(request);
					this.clients.add(newClient);
				}
			}
			return stream;
		}

		public void remove(HTTPClient client){
			this.clients.remove(client);
		}

		public boolean isEmpty(){
			return this.clients.isEmpty();
		}

		public void closeAll(){
			for(HTTPClient client : this.clients)
				client.close();
		}

		public void closeIf(java.util.function.Predicate<HTTPClient> pred){
			for(HTTPClient client : this.clients){
				if(pred.test(client))
					client.close();
			}
		}
	}


	protected static void logUNetError(Object... o) {
		if(HTTPCommon.USOCKET_ERROR_DEBUG)
			logger.debug(o);
		else
			logger.warn(o);
	}
}
