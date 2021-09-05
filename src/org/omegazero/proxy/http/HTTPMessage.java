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
	private boolean chunkedTransfer;

	private String origMethod;
	private String origScheme;
	private String origAuthority;
	private String origPath;
	private int origStatus;
	private String origVersion;

	private HTTPMessage correspondingMessage;

	private String requestId;
	private transient HTTPEngine engine;

	private int size;

	private Map<String, Object> attachments = null;

	private transient boolean locked = false;

	protected HTTPMessage(boolean request, Map<String, String> headers) {
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
	 * @param headers The HTTP headers
	 */
	public HTTPMessage(int status, String version, Map<String, String> headers) {
		this(false, headers);
		this.status = status;
		this.version = version;

		this.origStatus = status;
		this.origVersion = version;
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
	 * @param headers   The HTTP headers
	 */
	public HTTPMessage(String method, String scheme, String authority, String path, String version, Map<String, String> headers) {
		this(true, headers);
		this.method = method;
		this.scheme = scheme;
		this.authority = authority;
		this.path = path;
		this.version = version;

		this.origMethod = method;
		this.origScheme = scheme;
		this.origAuthority = authority;
		this.origPath = path;
		this.origVersion = version;
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
	public void setMethod(String method) {
		this.checkLocked();
		this.method = method;
	}

	@SideOnly(side = SideOnly.Side.REQUEST)
	public void setScheme(String scheme) {
		this.checkLocked();
		this.scheme = scheme;
	}

	@SideOnly(side = SideOnly.Side.REQUEST)
	public void setAuthority(String authority) {
		this.checkLocked();
		this.authority = authority;
	}

	@SideOnly(side = SideOnly.Side.REQUEST)
	public void setPath(String path) {
		this.checkLocked();
		this.path = path;
	}

	@SideOnly(side = SideOnly.Side.RESPONSE)
	public void setStatus(int status) {
		this.checkLocked();
		this.status = status;
	}

	public void setVersion(String version) {
		this.checkLocked();
		this.version = version;
	}

	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getOrigMethod() {
		return this.origMethod;
	}

	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getOrigScheme() {
		return this.origScheme;
	}

	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getOrigAuthority() {
		return this.origAuthority;
	}

	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getOrigPath() {
		return this.origPath;
	}

	@SideOnly(side = SideOnly.Side.RESPONSE)
	public int getOrigStatus() {
		return this.origStatus;
	}

	public String getOrigVersion() {
		return this.origVersion;
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
		this.checkLocked();
		Objects.requireNonNull(key);
		if(value == null)
			this.headerFields.remove(key);
		else
			this.headerFields.put(key, value);
	}

	/**
	 * Appends the given <b>value</b> to an existing header with the given <b>key</b>, separated by <b>separator</b>, or sets a header with the given <b>value</b> if no such
	 * header exists.
	 * 
	 * @param key       The HTTP field name of this header field
	 * @param value     The value to append to this header field, or the value of the header if it did not exist
	 * @param separator The separator between the existing value and the new value
	 */
	public void appendHeader(String key, String value, String separator) {
		this.checkLocked();
		Objects.requireNonNull(key);
		String val = this.getHeader(key);
		val = ((val != null) ? (val + Objects.requireNonNull(separator)) : "") + Objects.requireNonNull(value);
		this.setHeader(key, val);
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

	/**
	 * 
	 * @return Whether the message body is chunked, as set by {@link #setChunkedTransfer(boolean)} or by the application that created this <code>HTTPMessage</code> object
	 */
	public boolean isChunkedTransfer() {
		return this.chunkedTransfer;
	}

	/**
	 * Sets whether the body of this message should be transferred in chunks or as one piece with a predetermined size.<br>
	 * <br>
	 * If this is <code>false</code>, the length of the data set using {@link HTTPMessageData#setData(byte[])} must be the same as the original length.
	 * 
	 * @param chunkedTransfer
	 */
	public void setChunkedTransfer(boolean chunkedTransfer) {
		this.checkLocked();
		this.chunkedTransfer = chunkedTransfer;
	}

	@Deprecated
	public byte[] getData() {
		return new byte[0];
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
		this.checkLocked();
		this.requestId = Objects.requireNonNull(requestId);
	}

	public HTTPEngine getEngine() {
		return this.engine;
	}

	public void setEngine(HTTPEngine engine) {
		this.checkLocked();
		this.engine = engine;
	}


	public int getSize() {
		return this.size;
	}

	public void setSize(int size) {
		this.checkLocked();
		this.size = size;
	}


	/**
	 * Sets an object that is bound to this <code>HTTPMessage</code> identified by the given <b>key</b>. This may be used by applications to store message-exchange-specific
	 * data in an otherwise stateless environment.<br>
	 * <br>
	 * Values stored here have no meaning in HTTP and are purely intended to store metadata used by the application.
	 * 
	 * @param key   The string identifying the given <b>value</b> in this <code>HTTPMessage</code> object. Plugins are recommended to prepend the key with their respective
	 *              plugin IDs, followed by an underscore, to prevent conflicts
	 * @param value The value to be stored in this <code>HTTPMessage</code> object
	 */
	public void setAttachment(String key, Object value) {
		if(this.attachments == null)
			this.attachments = new HashMap<>();
		this.attachments.put(key, value);
	}

	/**
	 * Retrieves an attachment previously set by {@link #setAttachment(String, Object)}.
	 * 
	 * @param key The key of the attachment to return
	 * @return The value of the attachment, or <code>null</code> if no attachment with the given <b>key</b> exists
	 */
	public Object getAttachment(String key) {
		if(this.attachments == null)
			return null;
		else
			return this.attachments.get(key);
	}


	public void lock() {
		this.locked = true;
	}

	protected void checkLocked() {
		if(this.locked)
			throw new IllegalStateException("HTTPMessage object is locked may no longer be modified");
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
	 * Determines whether this <code>HTTPMessage</code> is an intermediate message, meaning it is followed up by another <code>HTTPMessage</code> to complete the HTTP exchange
	 * or it has some other special meaning.<br>
	 * This applies to responses with 1xx response codes.
	 * 
	 * @return <code>true</code> if this <code>HTTPMessage</code> is an intermediate message
	 * @since 3.2.2
	 */
	public boolean isIntermediateMessage() {
		return !this.request && this.status >= 100 && this.status <= 199;
	}

	/**
	 * 
	 * @return <code>true</code> if this <code>HTTPMessage</code> is a request that has received a response already
	 * @since 3.3.1
	 */
	public boolean hasResponse() {
		return this.request && this.getCorrespondingMessage() != null;
	}


	/**
	 * 
	 * @param response The response
	 * @since 3.3.1
	 * @see HTTPEngine#respond(HTTPMessage, HTTPMessageData)
	 */
	public void respond(HTTPMessageData response) {
		this.getEngine().respond(this, response);
	}

	/**
	 * 
	 * @param status  The status code of the response
	 * @param data    The data to send in the response
	 * @param headers Headers to send in the response. See {@link HTTPEngine#respond(HTTPMessage, int, byte[], String...)} for more information
	 * @since 3.3.1
	 * @see HTTPEngine#respond(HTTPMessage, int, byte[], String...)
	 */
	public void respond(int status, byte[] data, String... headers) {
		this.getEngine().respond(this, status, data, headers);
	}

	/**
	 * 
	 * @param status  The status code of the response
	 * @param title   Title of the error message
	 * @param message Error message
	 * @param headers Headers to send in the response. See {@link HTTPEngine#respond(HTTPMessage, int, byte[], String...)} for more information
	 * @since 3.3.1
	 * @see HTTPEngine#respondError(HTTPMessage, int, String, String, String...)
	 */
	public void respondError(int status, String title, String message, String... headers) {
		this.getEngine().respondError(this, status, title, message, headers);
	}


	/**
	 * Creates a mostly shallow copy of this <code>HTTPMessage</code> object.<br>
	 * <br>
	 * {@link #headerFields} and {@link #attachments} are the only objects where a new instance is created.
	 */
	@Override
	public HTTPMessage clone() {
		HTTPMessage c = new HTTPMessage(this.request, null);
		this.cloneData(c);
		return c;
	}

	/**
	 * Copies all class variables of this <code>HTTPMessage</code> into the given <code>HTTPMessage</code> object.
	 * 
	 * @param c The object to copy all variables into
	 * @since 3.3.1
	 */
	protected final synchronized void cloneData(HTTPMessage c) {
		c.method = this.method;
		c.scheme = this.scheme;
		c.authority = this.authority;
		c.path = this.path;
		c.status = this.status;
		c.version = this.version;
		c.headerFields.putAll(this.headerFields);
		c.origMethod = this.origMethod;
		c.origScheme = this.origScheme;
		c.origAuthority = this.origAuthority;
		c.origPath = this.origPath;
		c.origStatus = this.origStatus;
		c.origVersion = this.origVersion;
		c.correspondingMessage = this.correspondingMessage;
		c.requestId = this.requestId;
		c.engine = this.engine;
		c.size = this.size;
		if(this.attachments != null)
			c.attachments = new HashMap<>(this.attachments);
		c.chunkedTransfer = this.chunkedTransfer;
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
