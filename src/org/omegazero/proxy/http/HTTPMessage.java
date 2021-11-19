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
import java.util.Objects;

/**
 * Represents a generic HTTP request or response message, agnostic of the HTTP version used.
 */
public class HTTPMessage extends HTTPHeaderContainer implements Serializable {

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
	private boolean chunkedTransfer;

	private String origMethod;
	private String origScheme;
	private String origAuthority;
	private String origPath;
	private int origStatus;
	private String origVersion;

	private transient HTTPMessage correspondingMessage;

	private String requestId;
	private transient HTTPEngine engine;

	private int size;

	private transient Map<String, Object> attachments = null;

	private transient boolean locked = false;

	protected HTTPMessage(boolean request, Map<String, String> headers) {
		super(headers);
		this.request = request;
	}

	/**
	 * See {@link #HTTPMessage(int, String, Map)}.
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
	 * See {@link #HTTPMessage(String, String, String, String, String, Map)}.
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
	 * Returns <code>true</code> if this object represents a HTTP request, or <code>false</code> if this object represents a HTTP response.
	 * 
	 * @return Whether this object represents a HTTP request
	 */
	public final boolean isRequest() {
		return this.request;
	}

	/**
	 * Returns the time this {@link HTTPMessage} object was created, as returned by {@link System#currentTimeMillis()}.
	 * 
	 * @return The creation time of this object in milliseconds
	 */
	public long getCreatedTime() {
		return this.createdTime;
	}

	/**
	 * Returns the HTTP method of this HTTP request, or <code>null</code> if this {@link HTTPMessage} is not a HTTP request.
	 * 
	 * @return The HTTP request method
	 */
	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getMethod() {
		return this.method;
	}

	/**
	 * Returns the HTTP request scheme (URI component) of this HTTP request, or <code>null</code> if this {@link HTTPMessage} is not a HTTP request.
	 * 
	 * @return The HTTP request scheme
	 */
	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getScheme() {
		return this.scheme;
	}

	/**
	 * Returns the HTTP request authority (URI component) of this HTTP request, or <code>null</code> if this {@link HTTPMessage} is not a HTTP request or does not contain an
	 * authority component.
	 * 
	 * @return The HTTP request authority
	 */
	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getAuthority() {
		return this.authority;
	}

	/**
	 * Returns the HTTP request path (URI component) of this HTTP request, or <code>null</code> if this {@link HTTPMessage} is not a HTTP request.
	 * 
	 * @return The HTTP request path
	 */
	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getPath() {
		return this.path;
	}

	/**
	 * Returns the HTTP response status of this HTTP response, or <code>0</code> if this {@link HTTPMessage} is not a HTTP response.
	 * 
	 * @return The HTTP response status
	 */
	@SideOnly(side = SideOnly.Side.RESPONSE)
	public int getStatus() {
		return this.status;
	}

	/**
	 * Returns the HTTP version declared in this {@link HTTPMessage}.
	 * 
	 * @return The HTTP version string
	 */
	public String getVersion() {
		return this.version;
	}

	/**
	 * Sets the HTTP method of this HTTP request. Does not change the value returned by {@linkplain #getOrigVersion() <code>getOrig*</code> methods}.
	 * 
	 * @param method The new HTTP request method
	 */
	@SideOnly(side = SideOnly.Side.REQUEST)
	public void setMethod(String method) {
		this.checkLocked();
		this.method = method;
	}

	/**
	 * Sets the HTTP scheme (URI component) of this HTTP request. Does not change the value returned by {@linkplain #getOrigVersion() <code>getOrig*</code> methods}.
	 * 
	 * @param method The new HTTP request scheme
	 */
	@SideOnly(side = SideOnly.Side.REQUEST)
	public void setScheme(String scheme) {
		this.checkLocked();
		this.scheme = scheme;
	}

	/**
	 * Sets the HTTP authority (URI component) of this HTTP request. Does not change the value returned by {@linkplain #getOrigVersion() <code>getOrig*</code> methods}.
	 * 
	 * @param method The new HTTP request authority
	 */
	@SideOnly(side = SideOnly.Side.REQUEST)
	public void setAuthority(String authority) {
		this.checkLocked();
		this.authority = authority;
	}

	/**
	 * Sets the HTTP path (URI component) of this HTTP request. Does not change the value returned by {@linkplain #getOrigVersion() <code>getOrig*</code> methods}.
	 * 
	 * @param method The new HTTP request path
	 */
	@SideOnly(side = SideOnly.Side.REQUEST)
	public void setPath(String path) {
		this.checkLocked();
		this.path = path;
	}

	/**
	 * Sets the HTTP response status of this HTTP response. Does not change the value returned by {@linkplain #getOrigVersion() <code>getOrig*</code> methods}.
	 * 
	 * @param method The new HTTP response status
	 */
	@SideOnly(side = SideOnly.Side.RESPONSE)
	public void setStatus(int status) {
		this.checkLocked();
		this.status = status;
	}

	/**
	 * Sets the HTTP version string of this {@link HTTPMessage}. Does not change the value returned by {@linkplain #getOrigVersion() <code>getOrig*</code> methods}.
	 * 
	 * @param version The new version string
	 */
	public void setVersion(String version) {
		this.checkLocked();
		this.version = version;
	}

	/**
	 * See {@link #getMethod()}, and {@link #getOrigVersion()} about the <code>getOrig*</code> methods.
	 */
	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getOrigMethod() {
		return this.origMethod;
	}

	/**
	 * See {@link #getScheme()}, and {@link #getOrigVersion()} about the <code>getOrig*</code> methods.
	 */
	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getOrigScheme() {
		return this.origScheme;
	}

	/**
	 * See {@link #getAuthority()}, and {@link #getOrigVersion()} about the <code>getOrig*</code> methods.
	 */
	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getOrigAuthority() {
		return this.origAuthority;
	}

	/**
	 * See {@link #getPath()}, and {@link #getOrigVersion()} about the <code>getOrig*</code> methods.
	 */
	@SideOnly(side = SideOnly.Side.REQUEST)
	public String getOrigPath() {
		return this.origPath;
	}

	/**
	 * See {@link #getStatus()}, and {@link #getOrigVersion()} about the <code>getOrig*</code> methods.
	 */
	@SideOnly(side = SideOnly.Side.RESPONSE)
	public int getOrigStatus() {
		return this.origStatus;
	}

	/**
	 * See {@link #getVersion()}.<br>
	 * <br>
	 * This value is immutable (the same applies to all values returned by <code>getOrig*</code> methods), meaning it is always the value that was passed in the constructor,
	 * and cannot be changed with a call to {@link #setVersion(String)}. If {@link #setVersion(String)} was never called, {@link #getVersion()} and this method return the same
	 * value.
	 * 
	 * @return The initial HTTP version string
	 */
	public String getOrigVersion() {
		return this.origVersion;
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


	/**
	 * Returns this {@link HTTPMessage}s opposite <code>HTTPMessage</code>. If this <code>HTTPMessage</code> is a request, this method returns the requests response (may be
	 * <code>null</code>), and vice versa.
	 * 
	 * @return The opposite <code>HTTPMessage</code>
	 */
	public HTTPMessage getCorrespondingMessage() {
		return this.correspondingMessage;
	}

	public void setCorrespondingMessage(HTTPMessage correspondingMessage) {
		this.correspondingMessage = correspondingMessage;
	}


	/**
	 * Returns a request ID associated with this request by the engine using {@link HTTPMessage#setRequestId(String)}, <code>null</code> otherwise.
	 * 
	 * @return The string representation of the request ID
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

	/**
	 * Returns the {@link HTTPEngine} that created this {@link HTTPMessage}.
	 * 
	 * @return The <code>HTTPEngine</code>
	 */
	public HTTPEngine getEngine() {
		return this.engine;
	}

	public void setEngine(HTTPEngine engine) {
		this.checkLocked();
		this.engine = engine;
	}


	/**
	 * Returns the estimated size in bytes required to represent this {@link HTTPMessage} in the protocol. May be 0.
	 * 
	 * @return The estimated size in bytes
	 */
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

	@Override
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


	@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
	@java.lang.annotation.Target(value = { java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.METHOD })
	public @interface SideOnly {
		Side side();

		public enum Side {
			REQUEST, RESPONSE;
		}
	}
}
