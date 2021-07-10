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
package org.omegazero.proxy.core;

import org.omegazero.common.eventbus.Event;
import org.omegazero.common.eventbus.EventBus;
import org.omegazero.common.eventbus.EventResult;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http.HTTPMessage;
import org.omegazero.proxy.http.HTTPMessageData;
import org.omegazero.proxy.net.UpstreamServer;

public final class ProxyEvents {

	public static final Event PREINIT = new Event("onPreinit", new Class<?>[] {});
	public static final Event INIT = new Event("onInit", new Class<?>[] {});
	public static final Event SHUTDOWN = new Event("onShutdown", new Class<?>[] {});
	public static final Event DOWNSTREAM_CONNECTION = new Event("onDownstreamConnection", new Class<?>[] { SocketConnection.class });
	public static final Event DOWNSTREAM_CONNECTION_CLOSED = new Event("onDownstreamConnectionClosed", new Class<?>[] { SocketConnection.class });
	public static final Event INVALID_HTTP_REQUEST = new Event("onInvalidHTTPRequest", new Class<?>[] { SocketConnection.class, byte[].class });
	public static final Event INVALID_UPSTREAM_SERVER = new Event("onInvalidUpstreamServer", new Class<?>[] { SocketConnection.class, HTTPMessage.class });
	public static final Event INVALID_HTTP_RESPONSE = new Event("onInvalidHTTPResponse",
			new Class<?>[] { SocketConnection.class, SocketConnection.class, HTTPMessage.class, byte[].class });
	public static final Event HTTP_REQUEST_PRE_LOG = new Event("onHTTPRequestPreLog", new Class<?>[] { SocketConnection.class, HTTPMessage.class });
	public static final Event HTTP_REQUEST_PRE = new Event("onHTTPRequestPre", new Class<?>[] { SocketConnection.class, HTTPMessage.class, UpstreamServer.class });
	public static final Event HTTP_REQUEST = new Event("onHTTPRequest", new Class<?>[] { SocketConnection.class, HTTPMessage.class, UpstreamServer.class });
	public static final Event HTTP_REQUEST_DATA = new Event("onHTTPRequestData", new Class<?>[] { SocketConnection.class, HTTPMessageData.class, UpstreamServer.class });
	public static final Event HTTP_REQUEST_ENDED = new Event("onHTTPRequestEnded", new Class<?>[] { SocketConnection.class, HTTPMessage.class, UpstreamServer.class });
	public static final Event HTTP_RESPONSE = new Event("onHTTPResponse",
			new Class<?>[] { SocketConnection.class, SocketConnection.class, HTTPMessage.class, UpstreamServer.class });
	public static final Event HTTP_RESPONSE_DATA = new Event("onHTTPResponseData",
			new Class<?>[] { SocketConnection.class, SocketConnection.class, HTTPMessageData.class, UpstreamServer.class });
	public static final Event HTTP_RESPONSE_ENDED = new Event("onHTTPResponseEnded",
			new Class<?>[] { SocketConnection.class, SocketConnection.class, HTTPMessage.class, UpstreamServer.class });
	public static final Event SELECT_UPSTREAM_SERVER = new Event("selectUpstreamServer", new Class<?>[] { String.class, String.class }, UpstreamServer.class);
	public static final Event UPSTREAM_CONNECTION_PERMITTED = new Event("isUpstreamConnectionPermitted", false, new Class<?>[] { HTTPMessage.class, UpstreamServer.class },
			Boolean.class, true);
	public static final Event UPSTREAM_CONNECTION = new Event("onUpstreamConnection", new Class<?>[] { SocketConnection.class });
	public static final Event UPSTREAM_CONNECTION_CLOSED = new Event("onUpstreamConnectionClosed", new Class<?>[] { SocketConnection.class });
	public static final Event UPSTREAM_CONNECTION_ERROR = new Event("onUpstreamConnectionError", new Class<?>[] { SocketConnection.class, Throwable.class });
	public static final Event UPSTREAM_CONNECTION_TIMEOUT = new Event("onUpstreamConnectionTimeout", new Class<?>[] { SocketConnection.class });
	public static final Event MISSING_TLS_DATA = new Event("getMissingTLSData", new Class<?>[] { String.class, String.class }, java.util.Map.Entry.class);

	public static int runEvent(EventBus eventBus, Event event, Object... args) {
		int c;
		if(event.isStateful()){
			synchronized(event){
				c = eventBus.dispatchEvent(event, args);
				event.reset();
			}
		}else{
			c = eventBus.dispatchEvent(event, args);
		}
		return c;
	}

	public static EventResult runEventRes(EventBus eventBus, Event event, Object... args) {
		EventResult res;
		if(event.isStateful()){
			synchronized(event){
				res = eventBus.dispatchEventRes(event, args);
				event.reset();
			}
		}else{
			res = eventBus.dispatchEventRes(event, args);
		}
		return res;
	}
}
