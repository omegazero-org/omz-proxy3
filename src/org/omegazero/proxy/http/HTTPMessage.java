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

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a generic HTTP request or response message, agnostic of the HTTP version used.
 */
public class HTTPMessage implements Serializable {

	private static final long serialVersionUID = 1L;

	private final boolean request;

	private final long createdTime = System.currentTimeMillis();

	@SideOnly(side = SideOnly.Side.REQUEST)
	private String method;
	@SideOnly(side = SideOnly.Side.REQUEST)
	private String scheme;
	@SideOnly(side = SideOnly.Side.REQUEST)
	private String authority;
	@SideOnly(side = SideOnly.Side.REQUEST)
	private String path;
	@SideOnly(side = SideOnly.Side.RESPONSE)
	private int status;
	private String version;
	private final Map<String, String> headerFields;
	private byte[] data;

	private String origAuthority;
	private String origPath;

	private HTTPMessage correspondingMessage;

	private String requestId;
	private HTTPEngine engine;

	private int size;

	private HTTPMessage(boolean request, Map<String, String> headers) {
		this.request = request;
		if(headers == null)
			this.headerFields = new HashMap<>();
		else
			this.headerFields = headers;
	}

	/**
	 * @see #HTTPMessage(int, String, Map)
	 */
	public HTTPMessage(int status, String version) {
		this(status, version, null);
	}

	/**
	 * Creates a new <code>HTTPMessage</code> representing a HTTP response.
	 * 
	 * @param status  The status in the response
	 * @param version The HTTP version
	 */
	public HTTPMessage(int status, String version, Map<String, String> headers) {
		this(false, headers);
		this.status = status;
		this.version = version;
	}

	/**
	 * @see #HTTPMessage(String, String, String, String, String, Map)
	 */
	public HTTPMessage(String method, String scheme, String authority, String path, String version) {
		this(method, scheme, authority, path, version, null);
	}

	/**
	 * Creates a new <code>HTTPMessage</code> representing a HTTP request.
	 * 
	 * @param method    The request method
	 * @param scheme    The URL scheme (e.g. "http")
	 * @param authority The URL authority or, if not provided, the value of the "Host" header (e.g. "example.com")
	 * @param path      The requested path or URL
	 * @param version   The HTTP version
	 */
	public HTTPMessage(String method, String scheme, String authority, String path, String version, Map<String, String> headers) {
		this(true, headers);
		this.method = method;
		this.scheme = scheme;
		this.authority = authority;
		this.path = path;
		this.version = version;

		this.origAuthority = authority;
		this.origPath = path;
	}


	/**
	 * 
	 * @return <code>true</code> if this object represents a HTTP request, or <code>false</code> if this object represents a HTTP response
	 */
	public final boolean isRequest() {
		return this.request;
	}

	public long getCreatedTime() {
		return this.createdTime;
	}

	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getMethod() {
		return this.method;
	}

	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getScheme() {
		return this.scheme;
	}

	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getAuthority() {
		return this.authority;
	}

	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getPath() {
		return this.path;
	}

	@SideOnly(side = SideOnly.Side.RESPONSE)
	public int getStatus() {
		return this.status;
	}

	public String getVersion() {
		return this.version;
	}

	@SideOnly(side = SideOnly.Side.REQUEST)
	public void setAuthority(String authority) {
		this.authority = authority;
	}

	@SideOnly(side = SideOnly.Side.REQUEST)
	public void setPath(String path) {
		this.path = path;
	}

	public String getOrigAuthority() {
		return this.origAuthority;
	}

	public String getOrigPath() {
		return this.origPath;
	}

	/**
	 * 
	 * @param key The HTTP field name of this header field
	 * @return The value of this header field, or <code>null</code> if it does not exist
	 */
	public String getHeader(String key) {
		return this.headerFields.get(key);
	}

	/**
	 * 
	 * @param key The HTTP field name of this header field
	 * @param def A value to return if a header field with the specified name does not exist
	 * @return The value of this header field, or <b>def</b> if it does not exist
	 */
	public String getHeader(String key, String def) {
		String v = this.getHeader(key);
		if(v == null)
			v = def;
		return v;
	}

	/**
	 * 
	 * @param key   The HTTP field name of this header field
	 * @param value The value of this header field. If <code>null</code>, the header will be deleted
	 */
	public void setHeader(String key, String value) {
		Objects.requireNonNull(key);
		if(value == null)
			this.headerFields.remove(key);
		else
			this.headerFields.put(key, value);
	}

	/**
	 * 
	 * @param key The HTTP field name of the header to search for
	 * @return <code>true</code> if a header with the given key exists
	 */
	public boolean headerExists(String key) {
		return this.headerFields.containsKey(key);
	}

	/**
	 * Deletes the header entry with the given key.<br>
	 * <br>
	 * This call is equivalent to <code>setHeader(key, null)</code>.
	 * 
	 * @param key The header to delete
	 */
	public void deleteHeader(String key) {
		this.setHeader(key, null);
	}

	public Set<Entry<String, String>> getHeaderSet() {
		return this.headerFields.entrySet();
	}

	public byte[] getData() {
		return this.data;
	}

	public void setData(byte[] data) {
		this.data = data;
	}


	public HTTPMessage getCorrespondingMessage() {
		return this.correspondingMessage;
	}

	public void setCorrespondingMessage(HTTPMessage correspondingMessage) {
		this.correspondingMessage = correspondingMessage;
	}


	/**
	 * 
	 * @return A request ID associated with this request using {@link HTTPMessage#setRequestId(String)}, <code>null</code> otherwise
	 */
	public String getRequestId() {
		return this.requestId;
	}

	/**
	 * Sets a request ID associated with this request.
	 * 
	 * @param requestId The request ID
	 */
	public void setRequestId(String requestId) {
		this.requestId = Objects.requireNonNull(requestId);
	}

	public HTTPEngine getEngine() {
		return this.engine;
	}

	public void setEngine(HTTPEngine engine) {
		this.engine = engine;
	}


	public int getSize() {
		return this.size;
	}

	public void setSize(int size) {
		this.size = size;
	}


	/**
	 * 
	 * @return The request URI of this <code>HTTPMessage</code>
	 * @throws IllegalStateException If this object does not represent a HTTP request
	 */
	public String requestURI() {
		if(!this.request)
			throw new IllegalStateException("Called requestURI on a response object");
		return this.scheme + "://" + this.authority + ("*".equals(this.path) ? "" : this.path);
	}

	/**
	 * 
	 * @return A HTTP/1-style request line of the form <code>[method] [requestURI] HTTP/[version]</code>
	 * @throws IllegalStateException If this object does not represent a HTTP request
	 */
	public String requestLine() {
		if(!this.request)
			throw new IllegalStateException("Called requestLine on a response object");
		return this.method + " " + this.requestURI() + " " + this.version;
	}

	/**
	 * 
	 * @return A HTTP/1-style response line of the form <code>HTTP/[version] [status]</code>
	 * @throws IllegalStateException If this object does not represent a HTTP response
	 */
	public String responseLine() {
		if(this.request)
			throw new IllegalStateException("Called responseLine on a request object");
		return this.version + " " + this.status;
	}

	/**
	 * Checks if the start line of this <code>HTTPMessage</code> is equal to the start line of <b>msg</b>. The start line consists of:
	 * <ul>
	 * <li>for requests: the request method, the full URI (scheme, authority and path) and the HTTP version string</li>
	 * <li>for responses: the HTTP version string and status code</li>
	 * </ul>
	 * 
	 * This means that all values not relevant to the type of message are ignored. For example, if a HTTPMessage is a request ({@link HTTPMessage#isRequest()} returns
	 * <code>true</code>), the value of {@link HTTPMessage#status} is ignored, since it is only relevant for HTTP responses.<br>
	 * 
	 * @param msg The <code>HTTPMessage</code> to compare against
	 * @return <code>true</code> if the start line matches
	 */
	public boolean equalStartLine(HTTPMessage msg) {
		boolean startLineEqual;
		if(this.request != msg.request)
			startLineEqual = false;
		else if(this.request)
			startLineEqual = Objects.equals(this.method, msg.method) && Objects.equals(this.scheme, msg.scheme) && Objects.equals(this.authority, msg.authority)
					&& Objects.equals(this.path, msg.path) && Objects.equals(this.version, msg.version);
		else
			startLineEqual = Objects.equals(this.version, msg.version) && this.status == msg.status;
		return startLineEqual;
	}


	/**
	 * Creates a mostly shallow copy of this <code>HTTPMessage</code> object.<br>
	 * <br>
	 * {@link HTTPMessage#headerFields} is the only object where a new instance is created.
	 */
	@Override
	public synchronized HTTPMessage clone() {
		HTTPMessage c = new HTTPMessage(this.request, null);
		c.method = this.method;
		c.scheme = this.scheme;
		c.authority = this.authority;
		c.path = this.path;
		c.status = this.status;
		c.version = this.version;
		c.headerFields.putAll(this.headerFields);
		c.data = this.data;
		c.origAuthority = this.origAuthority;
		c.origPath = this.origPath;
		c.correspondingMessage = this.correspondingMessage;
		c.requestId = this.requestId;
		c.engine = this.engine;
		c.size = this.size;
		return c;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(32);
		sb.append("HTTPMessage[");
		if(this.request){
			sb.append("request").append("; method=").append(this.method).append("; path=").append(this.path).append("; version=").append(this.version);
		}else{
			sb.append("response").append("; status=").append(this.status).append("; version=").append(this.version);
		}
		sb.append("]");
		return sb.toString();
	}


	public @interface SideOnly {
		Side side();

		public enum Side {
			REQUEST, RESPONSE;
		}
	}
}
