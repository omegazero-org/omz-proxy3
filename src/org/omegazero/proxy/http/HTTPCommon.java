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
package org.omegazero.proxy.http;

import java.net.InetAddress;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;

import org.omegazero.common.util.PropertyUtil;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.core.Proxy;

public final class HTTPCommon {

	public static final int STATUS_CONTINUE = 100;
	public static final int STATUS_SWITCHING_PROTOCOLS = 101;
	public static final int STATUS_PROCESSING = 102;
	public static final int STATUS_OK = 200;
	public static final int STATUS_CREATED = 201;
	public static final int STATUS_ACCEPTED = 202;
	public static final int STATUS_NON_AUTHORITATIVE = 203;
	public static final int STATUS_NO_CONTENT = 204;
	public static final int STATUS_RESET_CONTENT = 205;
	public static final int STATUS_PARTIAL_CONTENT = 206;
	public static final int STATUS_MULTIPLE_CHOICES = 300;
	public static final int STATUS_MOVED_PERMANENTLY = 301;
	public static final int STATUS_FOUND = 302;
	public static final int STATUS_SEE_OTHER = 303;
	public static final int STATUS_NOT_MODIFIED = 304;
	public static final int STATUS_TEMPORARY_REDIRECT = 307;
	public static final int STATUS_PERMANENT_REDIRECT = 308;
	public static final int STATUS_BAD_REQUEST = 400;
	public static final int STATUS_UNAUTHORIZED = 401;
	public static final int STATUS_FORBIDDEN = 403;
	public static final int STATUS_NOT_FOUND = 404;
	public static final int STATUS_METHOD_NOT_ALLOWED = 405;
	public static final int STATUS_NOT_ACCEPTABLE = 406;
	public static final int STATUS_PROXY_AUTHENTICATION_REQUIRED = 407;
	public static final int STATUS_REQUEST_TIMEOUT = 408;
	public static final int STATUS_CONFLICT = 409;
	public static final int STATUS_GONE = 410;
	public static final int STATUS_LENGTH_REQUIRED = 411;
	public static final int STATUS_PRECONDITION_REQUIRED = 412;
	public static final int STATUS_PAYLOAD_TOO_LARGE = 413;
	public static final int STATUS_URI_TOO_LONG = 414;
	public static final int STATUS_UNSUPPORTED_MEDIA_TYPE = 415;
	public static final int STATUS_RANGE_NOT_SATISFIABLE = 416;
	public static final int STATUS_EXPECTATION_FAILED = 417;
	public static final int STATUS_IM_A_TEAPOT = 418;
	public static final int STATUS_UPGRADE_REQUIRED = 426;
	public static final int STATUS_PRECONDITION_FAILED = 428;
	public static final int STATUS_TOO_MANY_REQUESTS = 429;
	public static final int STATUS_REQUEST_HEADER_FIELDS_TOO_LARGE = 431;
	public static final int STATUS_UNAVAILABLE_FOR_LEGAL_REASONS = 451;
	public static final int STATUS_INTERNAL_SERVER_ERROR = 500;
	public static final int STATUS_NOT_IMPLEMENTED = 501;
	public static final int STATUS_BAD_GATEWAY = 502;
	public static final int STATUS_SERVICE_UNAVAILABLE = 503;
	public static final int STATUS_GATEWAY_TIMEOUT = 504;
	public static final int STATUS_HTTP_VERSION_NOT_SUPPORTED = 505;
	public static final int STATUS_VARIANT_ALSO_NEGOTIATES = 506;
	public static final int STATUS_LOOP_DETECTED = 508;
	public static final int STATUS_NOT_EXTENDED = 510;
	public static final int STATUS_NETWORK_AUTHENTICATION_REQUIRED = 511;

	private static final Random RANDOM = new Random();
	public static final int IADDR_HASH_SALT = PropertyUtil.getInt("org.omegazero.proxy.http.iaddrHashSalt", 42);
	public static final boolean USOCKET_ERROR_DEBUG = PropertyUtil.getBoolean("org.omegazero.http.upstreamSocketErrorDebug", false);

	private static final DateTimeFormatter DATE_HEADER_FORMATTER = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneId.of("GMT"));

	private static final String UPSTREAM_CONNECT_ERROR_MESSAGE = "An error occurred while connecting to the upstream server";
	private static final String UPSTREAM_ERROR_MESSAGE = "Error in connection to upstream server";

	/**
	 * 
	 * @return A date string formatted for use in the <i>Date</i> HTTP header
	 */
	public static String dateString() {
		return DATE_HEADER_FORMATTER.format(ZonedDateTime.now());
	}

	public static String hstrFromInetAddress(InetAddress address) {
		byte[] addrBytes = address.getAddress();
		int nf = 0;
		int bytesPerF = addrBytes.length >> 2;
		for(int i = 0; i < 4; i++){
			int n = 0;
			for(int j = 0; j < bytesPerF; j++){
				n += addrBytes[bytesPerF * i + j] + nf + IADDR_HASH_SALT;
			}
			nf |= (n & 0xff) << (i * 8);
		}
		if(nf <= 0x0fffffff)
			nf |= 0x80000000;
		return Integer.toHexString(nf);
	}

	public static String requestId(SocketConnection connection) {
		StringBuilder sb = new StringBuilder(32);
		int n = RANDOM.nextInt();
		if(n <= 0x0fffffff)
			n |= 0x10000000;
		sb.append(Integer.toHexString(n)).append(HTTPCommon.hstrFromInetAddress(((java.net.InetSocketAddress) connection.getRemoteAddress()).getAddress())).append('-')
				.append(Long.toHexString(System.currentTimeMillis()));
		return sb.toString();
	}

	public static String shortenRequestId(String full) {
		return full.substring(0, 8);
	}

	/**
	 * Sets several common HTTP headers.
	 * 
	 * @param proxy The proxy instance
	 * @param msg   The HTTP message to add headers to
	 */
	public static void setDefaultHeaders(Proxy proxy, HTTPMessage msg) {
		msg.appendHeader("via", msg.getVersion() + " " + proxy.getInstanceName(), ", ");
		msg.appendHeader("x-request-id", msg.getRequestId(), ",");
	}

	/**
	 * 
	 * @param e An error in an upstream connection
	 * @return An error message created from the given exception
	 */
	public static String getUpstreamErrorMessage(Throwable e) {
		if(e instanceof javax.net.ssl.SSLHandshakeException)
			return UPSTREAM_CONNECT_ERROR_MESSAGE + ": TLS handshake error";
		else if(e instanceof java.net.ConnectException)
			return UPSTREAM_CONNECT_ERROR_MESSAGE + ": " + e.getMessage();
		else if(e instanceof java.net.SocketException)
			return UPSTREAM_ERROR_MESSAGE + ": " + e.getMessage();
		else if(e instanceof InvalidHTTPMessageException)
			return "Invalid HTTP response from upstream server";
		else if(e instanceof java.io.IOException)
			return UPSTREAM_ERROR_MESSAGE + ": Socket error";
		else
			return UPSTREAM_ERROR_MESSAGE + ": Unexpected error";
	}


	/**
	 * Sets the response of the given <b>request</b> to <b>response</b> atomically and returns <code>true</code> if successful or <code>false</code> if the given request
	 * already has a response associated with it.
	 * 
	 * @param request  The request
	 * @param response The response
	 * @return <code>true</code> if successful, <code>false</code> if the request already had a response
	 * @since 3.3.1
	 */
	public static boolean setRequestResponse(HTTPMessage request, HTTPMessage response) {
		if(request != null){
			synchronized(request){
				if(request.getCorrespondingMessage() != null)
					return false;
				request.setCorrespondingMessage(response);
			}
		}
		return true;
	}

	/**
	 * Prepares the given <b>response</b> by performing one of the following:
	 * <ul>
	 * <li>If the response is a result of a request with the <i>HEAD</i> method, <b>data</b> is set to an empty array and any <i>Content-Length</i> header is deleted</li>
	 * <li>If the response should contain a response body ({@link #hasResponseBody(HTTPMessage, HTTPMessage)} returns <code>true</code>), the <i>Content-Length</i> header is
	 * set to the length of <b>data</b></li>
	 * <li>If the response should not contain a response body and <b>data</b> is empty, any <i>Content-Length</i> header is deleted</li>
	 * </ul>
	 * 
	 * @param request  The request that caused the response. May be <code>null</code>
	 * @param response The response
	 * @param data     Data of the response
	 * @return Possibly edited <b>data</b>
	 * @throws IllegalArgumentException If the response should not contain a response body, the <b>request</b> is not made with the <i>HEAD</i> method and data is non-empty
	 * @since 3.3.1
	 */
	public static byte[] prepareHTTPResponse(HTTPMessage request, HTTPMessage response, byte[] data) {
		if(request != null && request.getMethod().equals("HEAD")){
			if(data.length > 0)
				data = new byte[0];
			response.deleteHeader("content-length");
		}else if(HTTPCommon.hasResponseBody(request, response)){
			response.setHeader("content-length", String.valueOf(data.length));
		}else if(data.length > 0){
			throw new IllegalArgumentException("Response with status " + response.getStatus() + " must not have a response body");
		}else
			response.deleteHeader("content-length");
		return data;
	}


	/**
	 * 
	 * @param status A HTTP status code
	 * @return <code>true</code> if a response with the given status code should contain a response
	 * @since 3.3.1
	 * @see #hasResponseBody(HTTPMessage, HTTPMessage)
	 */
	public static boolean hasResponseBody(int status) {
		return !((status >= 100 && status <= 199) || status == 204 || status == 304);
	}

	/**
	 * 
	 * @param request  A HTTP request. May be <code>null</code>
	 * @param response A HTTP response
	 * @return <code>true</code> if the <b>response</b> initiated by the given <b>request</b> should contain a response body
	 * @since 3.3.1
	 * @see #hasResponseBody(int)
	 */
	public static boolean hasResponseBody(HTTPMessage request, HTTPMessage response) {
		// rfc 7230 section 3.3.3
		int status = response.getStatus();
		if(request != null){
			if(request.getMethod().equals("HEAD"))
				return false;
			if(request.getMethod().equals("CONNECT") && status >= 200 && status <= 299)
				return false;
		}
		return hasResponseBody(status);
	}
}
