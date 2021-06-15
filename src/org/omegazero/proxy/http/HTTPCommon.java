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

	private static final DateTimeFormatter DATE_HEADER_FORMATTER = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneId.of("GMT"));

	private static final String UPSTREAM_CONNECT_ERROR_MESSAGE = "An error occurred while connecting to the upstream server";
	private static final String UPSTREAM_ERROR_MESSAGE = "Error in connection to upstream server";

	public static String dateString() {
		return DATE_HEADER_FORMATTER.format(ZonedDateTime.now());
	}

	public static String hstrFromInetAddress(InetAddress address) {
		byte[] addrBytes = address.getAddress();
		int nf = 0;
		int bytesPerF = addrBytes.length / 4;
		for(int i = 0; i < 4; i++){
			int n = 0;
			for(int j = 0; j < bytesPerF; j++){
				n += (addrBytes[bytesPerF * i + j] + nf + 46) << i;
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

	public static void setDefaultHeaders(Proxy proxy, HTTPMessage msg) {
		msg.appendHeader("via", msg.getVersion() + " " + proxy.getInstanceName(), ", ");
		msg.appendHeader("x-request-id", msg.getRequestId(), ",");
	}

	public static String getUpstreamErrorMessage(Throwable e) {
		if(e instanceof javax.net.ssl.SSLHandshakeException)
			return UPSTREAM_CONNECT_ERROR_MESSAGE + ": TLS handshake error";
		else if(e instanceof java.net.ConnectException)
			return UPSTREAM_CONNECT_ERROR_MESSAGE + ": " + e.getMessage();
		else if(e instanceof java.net.SocketException)
			return UPSTREAM_ERROR_MESSAGE + ": " + e.getMessage();
		else if(e instanceof java.io.IOException)
			return UPSTREAM_ERROR_MESSAGE + ": Socket error";
		else
			return UPSTREAM_ERROR_MESSAGE + ": Unexpected error";
	}
}
