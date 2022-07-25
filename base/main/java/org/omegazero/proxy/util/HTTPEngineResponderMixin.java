/*
 * Copyright (C) 2022 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.util;

import org.omegazero.http.common.HTTPRequest;
import org.omegazero.http.common.HTTPResponse;
import org.omegazero.http.common.HTTPResponseData;
import org.omegazero.http.util.HTTPStatus;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.core.ProxyEvents;
import org.omegazero.proxy.http.HTTPCommon;
import org.omegazero.proxy.http.HTTPErrdoc;
import org.omegazero.proxy.net.UpstreamServer;

/**
 * A mixin containing utility methods for the {@link org.omegazero.proxy.http.HTTPEngine HTTPEngine} {@code respond} methods.
 * 
 * @since 3.6.1
 */
public interface HTTPEngineResponderMixin {


	public SocketConnection getDownstreamConnection();

	public void respond(HTTPRequest request, HTTPResponseData httpResponseData);

	/**
	 * Returns the HTTP version name string used for the {@code HTTPMessage} {@code version} field.
	 * 
	 * @return The HTTP version name
	 */
	public String getHTTPVersionName();


	/**
	 * Prepares an error response with the given parameters.
	 * <p>
	 * The {@link org.omegazero.proxy.http.HTTPEngine#respondError(HTTPRequest, int, String, String, String...) respondError} method can directly delegate to this method.
	 * 
	 * @param proxy The {@code Proxy} instance
	 * @param request The request to respond to
	 * @param status The status code of the response
	 * @param title Title of the error message
	 * @param message Error message
	 * @param headers Headers to send in the response
	 */
	public default void respondError(Proxy proxy, HTTPRequest request, int status, String title, String message, String... headers) {
		if(request != null && request.hasResponse())
			return;
		String accept = request != null ? request.getHeader("accept") : null;
		HTTPErrdoc errdoc = proxy.getErrdocForAccept(accept);
		String requestId = request != null ? request.getHeader("x-request-id") : null;
		byte[] errdocData = errdoc.generate(status, title, message, requestId, this.getDownstreamConnection().getApparentRemoteAddress().toString()).getBytes();
		this.respondEx(proxy, request, status, errdocData, headers, "content-type", errdoc.getMimeType());
	}

	/**
	 * Emits a <i>HTTP_FORWARD_FAILED</i> event on the {@code Proxy} event bus with the given parameters, and, if no response was sent, responds with an appropriate error message.
	 * <p>
	 * This method is used when the HTTP request could not be completed due to a problem with the upstream server.
	 * 
	 * @param proxy The {@code Proxy} instance
	 * @param request The request
	 * @param status The response status
	 * @param message The response error message
	 * @param uconn The upstream connection
	 * @param userver The upstream server
	 */
	public default void respondUNetError(Proxy proxy, HTTPRequest request, int status, String message, SocketConnection uconn, UpstreamServer userver) {
		if(request.hasResponse())
			return;
		proxy.dispatchEvent(ProxyEvents.HTTP_FORWARD_FAILED, this.getDownstreamConnection(), uconn, request, userver, status, message);
		if(!request.hasResponse())
			this.respondError(proxy, request, status, HTTPStatus.getStatusName(status), message);
	}

	/**
	 * Responds to the given <b>request</b> with the given parameters.
	 * <p>
	 * The {@link org.omegazero.http.util.HTTPResponder#respond(HTTPRequest, int, byte[], String...) respond} method can directly delegate to this method.
	 * 
	 * @param proxy The {@code Proxy} instance
	 * @param request The request to respond to
	 * @param status The status code of the response
	 * @param data The data to send in the response
	 * @param h1 The response headers
	 * @param h2 A second set of response headers
	 */
	public default void respondEx(Proxy proxy, HTTPRequest request, int status, byte[] data, String[] h1, String... h2) {
		if(request != null && request.hasResponse())
			return;
		HTTPResponse response = new HTTPResponse(status, this.getHTTPVersionName(), null);
		for(int i = 0; i + 1 < h2.length; i += 2)
			response.setHeader(h2[i], h2[i + 1]);
		for(int i = 0; i + 1 < h1.length; i += 2)
			response.setHeader(h1[i], h1[i + 1]);

		if(!response.headerExists("date"))
			response.setHeader("date", HTTPCommon.dateString());
		response.setHeader("server", proxy.getInstanceName());
		response.setHeader("x-proxy-engine", this.getClass().getSimpleName());
		if(request != null)
			response.setHeader("x-request-id", (String) request.getAttachment(HTTPCommon.ATTACHMENT_KEY_REQUEST_ID));

		this.respond(request, new HTTPResponseData(response, data));
	}
}
