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
import org.omegazero.http.common.HTTPMessage;
import org.omegazero.http.common.HTTPRequest;
import org.omegazero.http.common.HTTPResponse;
import org.omegazero.http.common.InvalidHTTPMessageException;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.core.Proxy;

public final class HTTPCommon {

	private static final Random RANDOM = new Random();
	public static final int IADDR_HASH_SALT = PropertyUtil.getInt("org.omegazero.proxy.http.iaddrHashSalt", 42);
	public static final boolean USOCKET_ERROR_DEBUG = PropertyUtil.getBoolean("org.omegazero.proxy.net.upstreamSocketErrorDebug", false);
	public static final String REQUEST_ID_SEPARATOR = PropertyUtil.getString("org.omegazero.proxy.http.requestId.separator", ",");
	public static final int REQUEST_ID_TIME_LENGTH = PropertyUtil.getInt("org.omegazero.proxy.http.requestId.timeLength", 0);
	public static final long REQUEST_ID_TIME_BASE = PropertyUtil.getLong("org.omegazero.proxy.http.requestId.timeBase", 0);

	private static final DateTimeFormatter DATE_HEADER_FORMATTER = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneId.of("GMT"));

	public static final String ATTACHMENT_KEY_REQUEST_ID = "engine_requestId";

	public static final String MSG_BAD_REQUEST = "The proxy server did not understand the request";
	public static final String MSG_NO_SERVER = "No appropriate upstream server was found for this request";
	public static final String MSG_REQUEST_TIMEOUT = "The client did not complete the request in time";
	public static final String MSG_SERVER_ERROR = "An unexpected internal error has occurred";
	public static final String MSG_UPSTREAM_CONNECT_FAILED = "An error occurred while connecting to the upstream server";
	public static final String MSG_UPSTREAM_CONNECT_TIMEOUT = "Connection to the upstream server timed out";
	public static final String MSG_UPSTREAM_RESPONSE_TIMEOUT = "The upstream server took too long to respond";
	public static final String MSG_UPSTREAM_RESPONSE_INVALID = "Invalid HTTP response from upstream server";
	public static final String MSG_UPSTREAM_CONNECTION_ERROR = "An error occurred in the connection to the upstream server";
	public static final String MSG_UPSTREAM_CONNECTION_CLOSED = "Connection to the upstream server closed unexpectedly";
	public static final String MSG_PROTO_NOT_SUPPORTED = "Unable to proxy request because the upstream server does not support ";


	/**
	 * Generates a date string formatted for use in the <i>Date</i> HTTP header.
	 * 
	 * @return The date string
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

	/**
	 * Generates a pseudo-random request ID from the given parameters and system properties.
	 * 
	 * @param connection The remote connection
	 * @return The request ID
	 */
	public static String requestId(SocketConnection connection) {
		int n = RANDOM.nextInt();
		if(n <= 0x0fffffff)
			n |= 0x10000000;
		StringBuilder sb = new StringBuilder(32);
		sb.append(Integer.toHexString(n)).append(HTTPCommon.hstrFromInetAddress(((java.net.InetSocketAddress) connection.getRemoteAddress()).getAddress()));
		if(REQUEST_ID_TIME_LENGTH > 0){
			sb.append('-');
			char[] buf = new char[REQUEST_ID_TIME_LENGTH];
			long val = Math.max(System.currentTimeMillis() - REQUEST_ID_TIME_BASE, 0);
			for(int i = buf.length - 1; i >= 0; i--){
				long p = val & 0xf;
				val >>>= 4;
				char c;
				if(p >= 10)
					c = (char) (p + 87);
				else
					c = (char) (p + 48);
				buf[i] = c;
			}
			sb.append(buf);
		}else if(REQUEST_ID_TIME_LENGTH == 0){
			sb.append('-');
			sb.append(Long.toHexString(System.currentTimeMillis()));
		}
		return sb.toString();
	}

	/**
	 * Shortens a request ID generated by {@link #requestId(SocketConnection)} to 8 characters, used in log outputs.
	 * 
	 * @param full The full request ID
	 * @return The shortened request ID
	 */
	public static String shortenRequestId(String full) {
		return full.substring(0, 8);
	}

	/**
	 * Sets several common HTTP headers.
	 * 
	 * @param proxy The proxy instance
	 * @param msg The HTTP message to add headers to
	 */
	public static void setDefaultHeaders(Proxy proxy, HTTPMessage msg) {
		msg.appendHeader("via", msg.getHttpVersion() + " " + proxy.getInstanceName(), ", ");
		msg.appendHeader("x-request-id", (String) (msg instanceof HTTPRequest ? msg : msg.getOther()).getAttachment(ATTACHMENT_KEY_REQUEST_ID), REQUEST_ID_SEPARATOR);
	}

	/**
	 * Generates an appropriate error message string from the given {@code Throwable} that occurred on an upstream connection.
	 * 
	 * @param e An error in an upstream connection
	 * @return An error message created from the given exception
	 */
	public static String getUpstreamErrorMessage(Throwable e) {
		if(e instanceof javax.net.ssl.SSLHandshakeException)
			return MSG_UPSTREAM_CONNECT_FAILED + ": TLS handshake error";
		else if(e instanceof java.net.ConnectException)
			return MSG_UPSTREAM_CONNECT_FAILED + ": " + e.getMessage();
		else if(e instanceof InvalidHTTPMessageException)
			return MSG_UPSTREAM_RESPONSE_INVALID + ": " + e.getMessage();
		else if(e instanceof java.io.IOException)
			return MSG_UPSTREAM_CONNECTION_ERROR + ": " + e;
		else
			return MSG_UPSTREAM_CONNECTION_ERROR + ": Unexpected internal error";
	}


	/**
	 * Sets the response of the given <b>request</b> to <b>response</b> atomically and returns <code>true</code> if successful or <code>false</code> if the given request already
	 * has a response associated with it.
	 * 
	 * @param request The request
	 * @param response The response
	 * @return <code>true</code> if successful, <code>false</code> if the request already had a response
	 * @since 3.3.1
	 */
	public static boolean setRequestResponse(HTTPRequest request, HTTPResponse response) {
		if(request != null){
			synchronized(request){
				if(request.hasResponse())
					return false;
				request.setOther(response);
			}
		}
		return true;
	}

	/**
	 * Prepares the given <b>response</b> by performing one of the following:
	 * <ul>
	 * <li>If the response is a result of a request with the <i>HEAD</i> method, <b>data</b> is set to an empty array and any <i>Content-Length</i> header is deleted</li>
	 * <li>If the response should contain a response body ({@link #hasResponseBody(HTTPMessage, HTTPMessage)} returns <code>true</code>), the <i>Content-Length</i> header is set to
	 * the length of <b>data</b></li>
	 * <li>If the response should not contain a response body and <b>data</b> is empty, any <i>Content-Length</i> header is deleted</li>
	 * </ul>
	 * 
	 * @param request The request that caused the response. May be <code>null</code>
	 * @param response The response
	 * @param data Data of the response
	 * @return Possibly edited <b>data</b>
	 * @throws IllegalArgumentException If the response should not contain a response body, the <b>request</b> is not made with the <i>HEAD</i> method and data is non-empty
	 * @since 3.3.1
	 */
	public static byte[] prepareHTTPResponse(HTTPRequest request, HTTPResponse response, byte[] data) {
		if(request != null && request.getMethod().equals("HEAD")){
			if(data.length > 0)
				data = new byte[0];
			response.deleteHeader("content-length");
		}else if(response.hasResponseBody(request)){
			response.setHeader("content-length", String.valueOf(data.length));
		}else if(data.length > 0){
			throw new IllegalArgumentException("Response with status " + response.getStatus() + " must not have a response body");
		}else
			response.deleteHeader("content-length");
		return data;
	}
}
